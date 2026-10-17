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
import java.util.List;

/** WebClient에서 공공데이터 API의 HTTP 200 위장 에러를 감지하는 필터. */
public class PublicDataErrorFilter implements ExchangeFilterFunction {
    private final PublicDataErrorDetector detector;
    /** 예외에서 값으로 가릴 인증키. 없으면 serviceKey 값만 가린다 */
    private final String serviceKey;

    public PublicDataErrorFilter() {
        this("00");
    }

    public PublicDataErrorFilter(String successCode) {
        this.detector = new PublicDataErrorDetector(successCode);
        this.serviceKey = null;
    }

    /**
     * 기본으로 찾는 이름(resultCode, RESULT_CODE, returnReasonCode, errorCode, ERROR_CODE)이 아닌 이름으로
     * 결과코드를 주는 API에 쓴다. 주어진 이름을 기본 이름보다 먼저 찾고, 없으면 기본 이름으로 찾는다.
     *
     * @param successCode 정상 처리를 뜻하는 결과코드
     * @param resultCodeKeys 결과코드가 담기는 XML 요소나 JSON 키의 이름(예: {@code resultCd}). 경로가 아니라 이름만 준다
     */
    public PublicDataErrorFilter(String successCode, List<String> resultCodeKeys) {
        this(successCode, resultCodeKeys, null);
    }

    /**
     * 인증키를 serviceKey가 아닌 이름의 질의(apiKey 등)나 주소 경로·요청 헤더로 보내는 API에 쓴다.
     * 예외의 요청 URI·결과메시지·바디 스니펫에서 키 값을 가린다({@link ServiceKeyMasker#mask(String, String)}).
     *
     * @param successCode 정상 처리를 뜻하는 결과코드
     * @param resultCodeKeys 결과코드가 담기는 XML 요소나 JSON 키의 이름. 기본 이름만 찾으면 빈 목록
     * @param serviceKey 요청에 넣는 인증키
     */
    public PublicDataErrorFilter(String successCode, List<String> resultCodeKeys, String serviceKey) {
        this.detector = new PublicDataErrorDetector(successCode, resultCodeKeys);
        this.serviceKey = serviceKey;
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
                // 키가 잘린 채 남지 않도록 자르기 전에 가린다
                throw new PublicDataApiException(mask(request.url().toString()), response.statusCode().value(),
                        result.resultCode(), mask(result.resultMsg()), truncate(mask(content), 500));
            }
        }
        return response.mutate()
                .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(rawBody)))
                .build();
    }

    private String mask(String text) {
        return ServiceKeyMasker.mask(text, serviceKey);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...(truncated)";
    }
}
