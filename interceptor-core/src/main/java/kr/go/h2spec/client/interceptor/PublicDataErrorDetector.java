package kr.go.h2spec.client.interceptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 공공데이터 응답 바디에서 성공/실패 여부와 오류 정보를 판정한다. */
final class PublicDataErrorDetector {
    private static final List<String> RESULT_CODE_KEYS = List.of("resultCode", "RESULT_CODE", "returnReasonCode", "errorCode", "ERROR_CODE");
    private static final List<String> RESULT_MSG_KEYS = List.of("resultMsg", "RESULT_MSG", "returnAuthMsg", "errorMsg", "ERROR_MSG");
    /** 사전에 없는, 결과코드가 딸리지 않는 오류 표현들. 나머지 키워드는 사전에서 가져온다. */
    private static final List<String> EXTRA_ERROR_KEYWORDS =
            List.of("HTTP ROUTING ERROR", "SERVICE ERROR", "잘못된 요청", "인증키가 유효하지");

    /**
     * 결과코드가 없는 응답을 판정할 키워드. 사전({@link PublicDataErrorCatalog})의 에러명을 그대로 쓰므로
     * 사전에 항목을 추가하면 탐지 범위도 함께 넓어진다.
     */
    private static final List<String> ERROR_KEYWORDS = errorKeywords();

    /** 결과코드 후보는 응답 메타데이터 영역까지만 탐색하고, 데이터 배열 내부는 내려가지 않는다. */
    private static final int MAX_SEARCH_DEPTH = 3;

    /**
     * XXE 방어 설정을 마친 팩토리. 설정 후에는 스레드 안전하므로 공유하고,
     * 스레드 안전하지 않은 DocumentBuilder만 호출마다 새로 만든다.
     */
    private static final DocumentBuilderFactory XML_FACTORY = secureXmlFactory();

    private static List<String> errorKeywords() {
        List<String> keywords = new ArrayList<>();
        for (PublicDataErrorCatalog.ErrorCode entry : PublicDataErrorCatalog.entries()) {
            keywords.add(entry.name());
            keywords.addAll(entry.nameAliases());
        }
        keywords.addAll(EXTRA_ERROR_KEYWORDS);
        // 긴 이름부터 맞춰야 짧은 이름이 부분 문자열로 먼저 걸리지 않는다
        keywords.sort((a, b) -> Integer.compare(b.length(), a.length()));
        return List.copyOf(keywords);
    }

    private static DocumentBuilderFactory secureXmlFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML 파서의 XXE 방어 설정에 실패했습니다", e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private final String successCode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    PublicDataErrorDetector(String successCode) { this.successCode = successCode; }

    DetectionResult detect(String content) {
        ResultInfo result = content.startsWith("<") ? parseXml(content) : parseJson(content);
        if (result.code != null) {
            return new DetectionResult(!successCode.equals(result.code), result.code, result.message);
        }
        // 결과코드가 없으면 본문의 에러 키워드로 판정한다. 어떤 키워드가 걸렸는지도
        // 남겨야 호출자가 원인을 알 수 있다.
        String keyword = matchedErrorKeyword(content);
        String message = result.message != null ? result.message : keyword;
        return new DetectionResult(keyword != null, null, message);
    }

    private ResultInfo parseXml(String content) {
        try {
            var builder = XML_FACTORY.newDocumentBuilder();
            try (InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                var document = builder.parse(input);
                return new ResultInfo(firstTagText(document, RESULT_CODE_KEYS), firstTagText(document, RESULT_MSG_KEYS));
            }
        } catch (Exception ignored) {
            return new ResultInfo(null, null);
        }
    }

    private String firstTagText(org.w3c.dom.Document document, List<String> names) {
        for (String name : names) {
            var nodes = document.getElementsByTagName(name);
            if (nodes.getLength() > 0) {
                String value = nodes.item(0).getTextContent();
                if (value != null && !value.isBlank()) return value.trim();
            }
        }
        return null;
    }

    private ResultInfo parseJson(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            return new ResultInfo(findByKeys(root, RESULT_CODE_KEYS), findByKeys(root, RESULT_MSG_KEYS));
        } catch (JsonProcessingException ignored) {
            return new ResultInfo(null, null);
        }
    }

    private String findByKeys(JsonNode root, List<String> keys) {
        for (String key : keys) {
            String value = findKey(root, key, 0);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String findKey(JsonNode node, String key, int depth) {
        if (depth > MAX_SEARCH_DEPTH || !node.isObject()) return null;
        JsonNode direct = node.get(key);
        if (direct != null && direct.isValueNode()) return direct.asText();
        for (JsonNode child : node) {
            String found = findKey(child, key, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private String matchedErrorKeyword(String content) {
        String upper = content.toUpperCase();
        return ERROR_KEYWORDS.stream()
                .filter(keyword -> upper.contains(keyword.toUpperCase()))
                .findFirst()
                .orElse(null);
    }

    static final class DetectionResult {
        private final boolean failure;
        private final String code;
        private final String message;
        DetectionResult(boolean failure, String code, String message) {
            this.failure = failure; this.code = code; this.message = message;
        }
        boolean isFailure() { return failure; }
        String resultCode() { return code; }
        String resultMsg() { return message; }
    }

    private record ResultInfo(String code, String message) {}
}
