package kr.go.h2spec.client.interceptor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 공공데이터포털 계열 API의 고질적인 문제를 해결하는 Interceptor.
 * <p>
 * 다수의 공공기관 API는 인증키 오류, 파라미터 오류, 호출 한도 초과 등 명백한
 * 실패 상황에서도 HTTP Status는 200 OK로 응답하고, 실제 성공/실패 여부는
 * 응답 바디 내부의 resultCode(또는 유사 필드)로만 알려준다.
 * <p>
 * 이 Interceptor는 응답 바디를 선제적으로 파싱하여 resultCode가 성공 코드(기본 "00")가
 * 아니거나, 알려진 에러 키워드가 포함된 경우 {@link PublicDataApiException}을 던져
 * 호출부가 200 OK만 보고 성공으로 오판하는 것을 방지한다.
 * <p>
 * <b>주의:</b> InputStream은 한 번만 읽을 수 있으므로, 이 Interceptor를 사용하는
 * RestTemplate/WebClient의 ClientHttpRequestFactory는 반드시
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory}로 감싸져 있어야
 * 이후 단계(HttpMessageConverter 등)에서 바디를 다시 읽을 수 있다.
 *
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate(
 *     new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
 * );
 * restTemplate.getInterceptors().add(new PublicDataErrorInterceptor());
 * }</pre>
 */
public class PublicDataErrorInterceptor implements ClientHttpRequestInterceptor {

    /** 정상 처리로 간주할 resultCode 값 (기관마다 다를 수 있어 생성기에서 override 가능) */
    private final String successCode;

    /** JSON/XML 어느 형식이든 대응하기 위한 resultCode 후보 키 목록 */
    private static final List<String> RESULT_CODE_KEYS = List.of(
            "resultCode", "RESULT_CODE", "returnReasonCode", "errorCode", "ERROR_CODE"
    );

    private static final List<String> RESULT_MSG_KEYS = List.of(
            "resultMsg", "RESULT_MSG", "returnAuthMsg", "errorMsg", "ERROR_MSG"
    );

    /** resultCode 필드 자체가 없는 비표준 응답을 대비한 키워드 폴백 */
    private static final List<String> ERROR_KEYWORDS = List.of(
            "SERVICE_KEY_IS_NOT_REGISTERED_ERROR",
            "INVALID_REQUEST_PARAMETER_ERROR",
            "NO_OPENAPI_SERVICE_ERROR",
            "SERVICE_ACCESS_DENIED_ERROR",
            "LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR",
            "DEADLINE_HAS_EXPIRED_ERROR",
            "UNKNOWN_ERROR",
            "HTTP ROUTING ERROR",
            "SERVICE ERROR",
            "잘못된 요청",
            "인증키가 유효하지"
    );

    /** JSON에서 결과코드 후보 키를 찾을 때 내려갈 최대 깊이 — 데이터 영역의 유사 필드 오인 방지 */
    private static final int MAX_SEARCH_DEPTH = 3;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public PublicDataErrorInterceptor() {
        this("00");
    }

    public PublicDataErrorInterceptor(String successCode) {
        this.successCode = successCode;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {

        ClientHttpResponse response = execution.execute(request, body);

        // BufferingClientHttpRequestFactory 전제 하에 여러 번 읽어도 안전하다.
        byte[] rawBody = StreamUtils.copyToByteArray(response.getBody());
        String content = new String(rawBody, StandardCharsets.UTF_8).trim();

        if (content.isEmpty()) {
            return response;
        }

        ResultInfo resultInfo = content.startsWith("<")
                ? parseXml(content)
                : parseJson(content);

        boolean isFailure = resultInfo.resultCode != null
                ? !successCode.equals(resultInfo.resultCode)
                : containsErrorKeyword(content);

        if (isFailure) {
            // 마스킹은 PublicDataApiException 생성자가 모든 필드에 일괄 적용한다
            throw new PublicDataApiException(
                    request.getURI().toString(),
                    response.getStatusCode().value(),
                    resultInfo.resultCode,
                    resultInfo.resultMsg,
                    truncate(content, 500)
            );
        }

        return response;
    }

    private ResultInfo parseXml(String content) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE 방지 표준 세트 (OWASP XXE Prevention Cheat Sheet)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            try (InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
                org.w3c.dom.Document doc = builder.parse(is);
                String code = firstNonEmptyTagText(doc, RESULT_CODE_KEYS);
                String msg = firstNonEmptyTagText(doc, RESULT_MSG_KEYS);
                return new ResultInfo(code, msg);
            }
        } catch (Exception e) {
            // 파싱 실패 시 resultCode를 확정할 수 없으므로 키워드 폴백으로 넘긴다.
            return new ResultInfo(null, null);
        }
    }

    private String firstNonEmptyTagText(org.w3c.dom.Document doc, List<String> tagNames) {
        for (String tag : tagNames) {
            org.w3c.dom.NodeList nodes = doc.getElementsByTagName(tag);
            if (nodes.getLength() > 0) {
                String text = nodes.item(0).getTextContent();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    private ResultInfo parseJson(String content) {
        try {
            JsonNode root = objectMapper.readTree(content);
            String code = findByKeys(root, RESULT_CODE_KEYS);
            String msg = findByKeys(root, RESULT_MSG_KEYS);
            return new ResultInfo(code, msg);
        } catch (JsonProcessingException e) {
            // JSON으로 파싱되지 않으면 resultCode를 확정할 수 없으므로 키워드 폴백으로 넘긴다
            return new ResultInfo(null, null);
        }
    }

    /**
     * 후보 키를 우선순위 순서대로, 객체 트리의 얕은 깊이에서만 탐색한다.
     * 배열(items 등) 내부는 응답 데이터 영역이므로 내려가지 않는다 —
     * 데이터에 errorCode 같은 필드가 있어도 결과코드로 오인하지 않기 위함.
     */
    private String findByKeys(JsonNode root, List<String> keys) {
        for (String key : keys) {
            String value = findKey(root, key, 0);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String findKey(JsonNode node, String key, int depth) {
        if (depth > MAX_SEARCH_DEPTH || !node.isObject()) {
            return null;
        }
        JsonNode direct = node.get(key);
        if (direct != null && direct.isValueNode()) {
            return direct.asText();
        }
        for (JsonNode child : node) {
            String found = findKey(child, key, depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean containsErrorKeyword(String content) {
        String upper = content.toUpperCase();
        for (String keyword : ERROR_KEYWORDS) {
            if (upper.contains(keyword.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }

    private static class ResultInfo {
        final String resultCode;
        final String resultMsg;

        ResultInfo(String resultCode, String resultMsg) {
            this.resultCode = resultCode;
            this.resultMsg = resultMsg;
        }
    }
}
