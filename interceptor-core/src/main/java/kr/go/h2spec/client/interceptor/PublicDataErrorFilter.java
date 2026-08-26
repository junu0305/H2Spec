package kr.go.h2spec.client.interceptor;

import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

/** WebClient에서 공공데이터 API의 HTTP 200 위장 에러를 감지하는 필터. */
public class PublicDataErrorFilter implements ExchangeFilterFunction {
    private final PublicDataErrorDetector detector;

    public PublicDataErrorFilter() { this("00"); }
    public PublicDataErrorFilter(String successCode) { this.detector = new PublicDataErrorDetector(successCode); }

    @Override
    public reactor.core.publisher.Mono<ClientResponse> filter(
            org.springframework.web.reactive.function.client.ClientRequest request,
            ExchangeFunction next) {
        return next.exchange(request).flatMap(response ->
                response.bodyToMono(byte[].class)
                        .defaultIfEmpty(new byte[0])
                        .map(rawBody -> rebuildOrThrow(request, response, rawBody)));
    }

    private ClientResponse rebuildOrThrow(
            org.springframework.web.reactive.function.client.ClientRequest request,
            ClientResponse response, byte[] rawBody) {
        String content = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (!content.isEmpty()) {
            PublicDataErrorDetector.DetectionResult result = detector.detect(content);
            if (result.isFailure()) {
                throw new PublicDataApiException(request.url().toString(), response.statusCode().value(),
                        result.resultCode(), result.resultMsg(), truncate(content, 500));
            }
        }
        return ClientResponse.from(response)
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(rawBody)))
                .build();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }
}
