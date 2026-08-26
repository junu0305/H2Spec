package kr.go.h2spec.client.interceptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 공공데이터 응답 바디에서 성공/실패 여부와 오류 정보를 판정한다. */
final class PublicDataErrorDetector {
    private static final List<String> RESULT_CODE_KEYS = List.of("resultCode", "RESULT_CODE", "returnReasonCode", "errorCode", "ERROR_CODE");
    private static final List<String> RESULT_MSG_KEYS = List.of("resultMsg", "RESULT_MSG", "returnAuthMsg", "errorMsg", "ERROR_MSG");
    private static final List<String> ERROR_KEYWORDS = List.of(
            "SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "INVALID_REQUEST_PARAMETER_ERROR", "NO_OPENAPI_SERVICE_ERROR",
            "SERVICE_ACCESS_DENIED_ERROR", "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR", "DEADLINE_HAS_EXPIRED_ERROR",
            "UNKNOWN_ERROR", "HTTP ROUTING ERROR", "SERVICE ERROR", "잘못된 요청", "인증키가 유효하지");
    private static final int MAX_SEARCH_DEPTH = 3;

    private final String successCode;
    private final ObjectMapper objectMapper = new ObjectMapper();

    PublicDataErrorDetector(String successCode) { this.successCode = successCode; }

    DetectionResult detect(String content) {
        ResultInfo result = content.startsWith("<") ? parseXml(content) : parseJson(content);
        boolean failure = result.code != null ? !successCode.equals(result.code) : containsErrorKeyword(content);
        return new DetectionResult(failure, result.code, result.message);
    }

    private ResultInfo parseXml(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
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

    private boolean containsErrorKeyword(String content) {
        String upper = content.toUpperCase();
        return ERROR_KEYWORDS.stream().anyMatch(keyword -> upper.contains(keyword.toUpperCase()));
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
