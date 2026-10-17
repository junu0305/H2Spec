package kr.go.h2spec.client.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicDataErrorFilterTest {
    private final PublicDataErrorFilter filter = new PublicDataErrorFilter();
    private final ClientRequest request = ClientRequest.create(HttpMethod.GET, URI.create("http://api.example/test"))
            .build();

    @Test
    void 정상_응답은_검사_후에도_downstream에서_바디를_읽을_수_있다() {
        ClientResponse response = apply("{\"resultCode\":\"00\",\"resultMsg\":\"OK\"}");

        assertEquals("{\"resultCode\":\"00\",\"resultMsg\":\"OK\"}", response.bodyToMono(String.class).block());
    }

    @Test
    void 큰_응답도_코덱_메모리_한도와_무관하게_처리한다() {
        String body = "x".repeat(400 * 1024);

        ClientResponse response = apply(body);

        byte[] actual = DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), Integer.MAX_VALUE)
                .map(buffer -> {
                    try {
                        byte[] bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                        return bytes;
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                })
                .block();

        assertArrayEquals(body.getBytes(java.nio.charset.StandardCharsets.UTF_8), actual);
    }

    @Test
    void resultCode가_실패면_예외를_던진다() {
        PublicDataApiException exception = assertThrows(PublicDataApiException.class,
                () -> apply("{\"resultCode\":\"30\",\"resultMsg\":\"SERVICE ERROR\"}"));

        assertEquals("30", exception.getResultCode());
        assertEquals("http://api.example/test", exception.getRequestUri());
    }

    @Test
    void resultCode가_없어도_알려진_에러_키워드를_감지한다() {
        assertThrows(PublicDataApiException.class, () -> apply("SERVICE ERROR"));
    }

    @Test
    void 결과코드_이름을_주면_그_이름으로_찾는다() {
        PublicDataErrorFilter withKey = new PublicDataErrorFilter("00", List.of("resultCd"));

        PublicDataApiException exception = assertThrows(PublicDataApiException.class,
                () -> apply(withKey, "{\"result\":{\"resultCd\":\"30\",\"resultMsg\":\"SERVICE ERROR\"}}"));

        assertEquals("30", exception.getResultCode());
        assertEquals("{\"result\":{\"resultCd\":\"00\"}}",
                apply(withKey, "{\"result\":{\"resultCd\":\"00\"}}").bodyToMono(String.class).block());
    }

    @Test
    void 키를_주면_예외에서_경로에_든_키_값을_가린다() {
        PublicDataErrorFilter withKey = new PublicDataErrorFilter("00", List.of(), "SECRET/KEY=");
        ClientRequest keyed = ClientRequest.create(HttpMethod.GET, URI.create("http://api.example/svc/SECRET%2FKEY%3D"))
                .build();
        ClientResponse response = ClientResponse.create(HttpStatus.OK)
                .body("<response><header><resultCode>30</resultCode><resultMsg>SECRET/KEY=</resultMsg></header></response>")
                .build();

        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> withKey.filter(keyed, ignored -> Mono.just(response)).block());

        assertEquals("http://api.example/svc/****", e.getRequestUri());
        assertFalse(e.getMessage().contains("SECRET"), e.getMessage());
        assertFalse(e.getRawBodySnippet().contains("SECRET"), e.getRawBodySnippet());
    }

    private ClientResponse apply(String body) {
        return apply(filter, body);
    }

    private ClientResponse apply(PublicDataErrorFilter target, String body) {
        ClientResponse response = ClientResponse.create(HttpStatus.OK).body(body).build();
        return target.filter(request, ignored -> Mono.just(response)).block();
    }
}
