package kr.go.h2spec.generator;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * 생성 클라이언트가 붙이는 인터셉터에 응답 바디를 흘려 보는 테스트 유틸리티.
 * generator의 테스트 classpath에는 spring-test가 없어 요청과 응답을 직접 만든다.
 */
public final class InterceptorProbe {

    private InterceptorProbe() {
    }

    /** body가 HTTP 200으로 온 것처럼 인터셉터를 부른다. 인터셉터가 오류 응답으로 보면 PublicDataApiException이 난다 */
    public static void respond(ClientHttpRequestInterceptor interceptor, String body) throws IOException {
        HttpRequest request = new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return HttpMethod.GET;
            }

            @Override
            public URI getURI() {
                return URI.create("http://api.example/test");
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ClientHttpResponse response = new ClientHttpResponse() {
            @Override
            public HttpStatusCode getStatusCode() {
                return HttpStatus.OK;
            }

            @Override
            public String getStatusText() {
                return "OK";
            }

            @Override
            public void close() {
            }

            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }
        };
        interceptor.intercept(request, new byte[0], (req, requestBody) -> response);
    }
}
