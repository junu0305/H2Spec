package kr.go.h2spec.client.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 공공데이터포털 계열 API의 HTTP 200 위장 에러를 감지하는 RestTemplate 인터셉터.
 * <p>
 * 응답 바디의 resultCode(또는 유사 필드)가 성공 코드가 아니거나 알려진 에러
 * 키워드를 포함하면 {@link PublicDataApiException}을 던집니다.
 * <p>
 * <b>주의:</b> InputStream은 한 번만 읽을 수 있으므로, 이 인터셉터를 사용하는
 * RestTemplate의 ClientHttpRequestFactory는 반드시
 * {@link org.springframework.http.client.BufferingClientHttpRequestFactory}로 감싸야
 * 이후 메시지 컨버터가 응답 바디를 다시 읽을 수 있습니다.
 *
 * <pre>{@code
 * RestTemplate restTemplate = new RestTemplate(
 *     new BufferingClientHttpRequestFactory(new SimpleClientHttpRequestFactory())
 * );
 * restTemplate.getInterceptors().add(new PublicDataErrorInterceptor());
 * }</pre>
 */
public class PublicDataErrorInterceptor implements ClientHttpRequestInterceptor {
    private final PublicDataErrorDetector detector;

    public PublicDataErrorInterceptor() { this("00"); }
    public PublicDataErrorInterceptor(String successCode) { this.detector = new PublicDataErrorDetector(successCode); }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        byte[] rawBody = StreamUtils.copyToByteArray(response.getBody());
        String content = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (content.isEmpty()) return response;

        PublicDataErrorDetector.DetectionResult result = detector.detect(content);
        if (result.isFailure()) {
            throw new PublicDataApiException(request.getURI().toString(), response.getStatusCode().value(),
                    result.resultCode(), result.resultMsg(), truncate(content, 500));
        }
        return response;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }
}
