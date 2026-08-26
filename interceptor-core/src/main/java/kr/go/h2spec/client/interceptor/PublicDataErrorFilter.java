package kr.go.h2spec.client.interceptor;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/** WebClient에서 공공데이터 API의 HTTP 200 위장 에러를 감지하는 필터. */
public class PublicDataErrorFilter implements ExchangeFilterFunction {
    private final PublicDataErrorDetector detector;

    public PublicDataErrorFilter() {
        this("00");
    }

    public PublicDataErrorFilter(String successCode) {
        this.detector = new PublicDataErrorDetector(successCode);
    }

    @Override
    public Mono<ClientResponse> filter(
            ClientRequest request,
            ExchangeFunction next) {
        return next.exchange(request).flatMap(response ->
                DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), Integer.MAX_VALUE)
                        .map(buffer -> readBytes(buffer))
                        .defaultIfEmpty(new byte[0])
                        .map(rawBody -> rebuildOrThrow(request, response, rawBody)));
    }

    private byte[] readBytes(DataBuffer buffer) {
        try {
            byte[] rawBody = new byte[buffer.readableByteCount()];
            buffer.read(rawBody);
            return rawBody;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private ClientResponse rebuildOrThrow(
            ClientRequest request,
            ClientResponse response, byte[] rawBody) {
        String content = new String(rawBody, StandardCharsets.UTF_8).trim();
        if (!content.isEmpty()) {
            PublicDataErrorDetector.DetectionResult result = detector.detect(content);
            if (result.isFailure()) {
                throw new PublicDataApiException(request.url().toString(), response.statusCode().value(),
                        result.resultCode(), result.resultMsg(), truncate(content, 500));
            }
        }
        return response.mutate()
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(rawBody)))
                .build();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }
}
