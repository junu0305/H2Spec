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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    /** 숫자로만 된 성공코드의 앞자리 0을 뗀 꼴. 숫자로만 되어 있지 않으면 null이고 글자 그대로 비교한다 */
    private final String numericSuccessCode;
    /** 결과코드를 찾을 이름. 호출자가 준 이름을 기본 이름({@link #RESULT_CODE_KEYS})보다 먼저 찾는다 */
    private final List<String> resultCodeKeys;
    private final ObjectMapper objectMapper = new ObjectMapper();

    PublicDataErrorDetector(String successCode) { this(successCode, List.of()); }

    PublicDataErrorDetector(String successCode, List<String> extraResultCodeKeys) {
        this.successCode = successCode;
        this.numericSuccessCode = isDigitsOnly(successCode) ? stripLeadingZeros(successCode) : null;
        this.resultCodeKeys = resultCodeKeys(extraResultCodeKeys);
    }

    /**
     * 문서에 적힌 이름이 그 API의 결과코드를 가장 잘 가리키므로 기본 이름보다 앞에 둔다.
     * 기본 이름을 다시 주면 앞으로 옮겨 한 번만 찾는다.
     */
    private static List<String> resultCodeKeys(List<String> extraKeys) {
        Set<String> keys = new LinkedHashSet<>(extraKeys);
        keys.addAll(RESULT_CODE_KEYS);
        return List.copyOf(keys);
    }

    DetectionResult detect(String content) {
        ResultInfo result = content.startsWith("<") ? parseXml(content) : parseJson(content);
        if (result.code != null) {
            return new DetectionResult(!isSuccessCode(result.code), result.code, result.message);
        }
        // 결과코드가 없으면 본문의 에러 키워드로 판정한다. 어떤 키워드가 걸렸는지도
        // 남겨야 호출자가 원인을 알 수 있다.
        String keyword = matchedErrorKeyword(content);
        String message = result.message != null ? result.message : keyword;
        return new DetectionResult(keyword != null, null, message);
    }

    /**
     * 숫자로만 된 두 코드는 앞자리 0을 떼고 비교해 0, 00, 000을 같은 코드로 본다.
     * 문서에는 성공코드가 00인데 실제 응답은 0이나 JSON 숫자 0({@code "resultCode": 0})으로 오는 기관이 있다.
     * 앞자리 0만 다른 숫자 코드는 같은 수를 뜻하므로 오류 코드를 성공으로 잘못 볼 일이 없다.
     * 사전({@link PublicDataErrorCatalog#find})도 같은 이유로 자릿수를 무시한다.
     * IR의 성공코드 정책은 문서마다 엇갈리는 0·00·000을 00으로 적고, 이 비교를 안전장치로 삼는다.
     * 숫자가 아닌 코드(INFO-00 등)는 대소문자까지 같아야 성공이다.
     */
    private boolean isSuccessCode(String code) {
        if (successCode.equals(code)) return true;
        return numericSuccessCode != null && isDigitsOnly(code) && numericSuccessCode.equals(stripLeadingZeros(code));
    }

    /** 결과코드는 ASCII 숫자만 쓰므로 다른 문자 체계의 숫자는 숫자 코드로 보지 않는다 */
    private static boolean isDigitsOnly(String code) {
        return code != null && !code.isEmpty() && code.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** 수로 바꾸지 않고 글로 다룬다. 자릿수가 긴 코드에서 넘치거나 예외가 나지 않게 한다. 0으로만 된 코드는 0이 된다 */
    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }

    private ResultInfo parseXml(String content) {
        try {
            var builder = XML_FACTORY.newDocumentBuilder();
            try (InputStream input = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                var document = builder.parse(input);
                return new ResultInfo(resultCodeText(document), firstTagText(document, RESULT_MSG_KEYS, Integer.MAX_VALUE));
            }
        } catch (Exception ignored) {
            return new ResultInfo(null, null);
        }
    }

    /**
     * 결과코드를 찾는다. 호출자가 준 이름은 JSON과 같이 응답 메타데이터 깊이({@link #MAX_SEARCH_DEPTH})까지만 찾는다.
     * {@code code}처럼 흔한 이름이면 데이터 항목 안의 같은 이름 요소가 먼저 잡혀 정상 응답을 오류로 볼 수 있기 때문이다.
     * 기본 이름은 지금까지처럼 문서 전체에서 찾는다.
     */
    private String resultCodeText(org.w3c.dom.Document document) {
        for (String key : resultCodeKeys) {
            int maxDepth = RESULT_CODE_KEYS.contains(key) ? Integer.MAX_VALUE : MAX_SEARCH_DEPTH;
            String value = firstTagText(document, List.of(key), maxDepth);
            if (value != null) return value;
        }
        return null;
    }

    /** @param maxDepth 루트 요소를 0으로 센 요소 깊이의 상한 */
    private String firstTagText(org.w3c.dom.Document document, List<String> names, int maxDepth) {
        for (String name : names) {
            var nodes = document.getElementsByTagName(name);
            for (int i = 0; i < nodes.getLength(); i++) {
                if (depth(nodes.item(i)) > maxDepth) continue;
                String value = nodes.item(i).getTextContent();
                if (value != null && !value.isBlank()) return value.trim();
                // 문서 전체에서 찾던 기본 이름은 지금까지처럼 첫 요소만 본다
                if (maxDepth == Integer.MAX_VALUE) break;
            }
        }
        return null;
    }

    private static int depth(org.w3c.dom.Node node) {
        int depth = 0;
        for (org.w3c.dom.Node parent = node.getParentNode(); parent != null
                && parent.getNodeType() == org.w3c.dom.Node.ELEMENT_NODE; parent = parent.getParentNode()) {
            depth++;
        }
        return depth;
    }

    private ResultInfo parseJson(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            return new ResultInfo(findByKeys(root, resultCodeKeys), findByKeys(root, RESULT_MSG_KEYS));
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
        // null은 값이 없는 것이다. "null"이라는 결과코드로 읽으면 정상 응답을 오류로 본다
        if (direct != null && direct.isValueNode() && !direct.isNull()) return direct.asText();
        for (JsonNode child : node) {
            String found = findKey(child, key, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private String matchedErrorKeyword(String content) {
        String upper = content.toUpperCase(Locale.ROOT);
        return ERROR_KEYWORDS.stream()
                .filter(keyword -> upper.contains(keyword.toUpperCase(Locale.ROOT)))
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
