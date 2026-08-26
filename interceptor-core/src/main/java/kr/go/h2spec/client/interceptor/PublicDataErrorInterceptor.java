package kr.go.h2spec.client.interceptor;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** RestTemplate에서 공공데이터 API의 HTTP 200 위장 에러를 감지하는 인터셉터. */
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
