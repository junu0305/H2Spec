package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 컴파일한 생성 클라이언트를 실제로 불러 보는 테스트 유틸리티. 네트워크 대신 요청을 기록하고 정해 둔 바디로 답한다.
 * 생성 클라이언트가 만드는 기본 RestTemplate(인터셉터 포함)에 이 요청 팩토리를 끼우므로, 인터셉터와 매퍼까지 생성 코드 그대로 돈다.
 * generator의 테스트 classpath에는 spring-test가 없어 요청과 응답을 직접 만든다.
 */
public final class GeneratedClientCall implements ClientHttpRequestFactory {

    private final String responseBody;
    private URI uri;
    private HttpHeaders headers;

    /** @param responseBody HTTP 200으로 돌려줄 바디 */
    public GeneratedClientCall(String responseBody) {
        this.responseBody = responseBody;
    }

    /**
     * 생성 클라이언트를 만들어 API 메서드를 부른다.
     *
     * @param classesDir  생성 코드를 컴파일한 디렉터리
     * @param clientClass 클라이언트 클래스 이름
     * @param serviceKey  생성자에 넘길 인증키. 인증키를 받지 않는 클라이언트면 쓰지 않는다
     * @param args        API 메서드의 인자
     * @return 응답 DTO를 JSON 트리로 바꾼 것(getter의 이름과 @JsonProperty의 원래 이름을 따른다)
     * @throws Throwable API 메서드가 던진 예외 그대로
     */
    public JsonNode call(Path classesDir, String clientClass, String serviceKey, Object... args) throws Throwable {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classesDir.toUri().toURL()},
                GeneratedClientCall.class.getClassLoader())) {
            Class<?> client = loader.loadClass(clientClass);
            Method factory = staticMethod(client, "defaultRestTemplate");
            factory.setAccessible(true);
            RestTemplate restTemplate = (RestTemplate) (factory.getParameterCount() == 0
                    ? factory.invoke(null)
                    : factory.invoke(null, serviceKey));
            restTemplate.setRequestFactory(this);
            // 인증키를 보내지 않는 클라이언트는 키를 받지 않는다
            Object instance = hasConstructor(client, String.class, RestTemplate.class)
                    ? client.getConstructor(String.class, RestTemplate.class).newInstance(serviceKey, restTemplate)
                    : client.getConstructor(RestTemplate.class).newInstance(restTemplate);
            try {
                return new ObjectMapper().valueToTree(apiMethod(client).invoke(instance, args));
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /** @return 마지막으로 보낸 요청의 주소 */
    public URI uri() {
        return uri;
    }

    /** @return 마지막으로 보낸 요청의 헤더 */
    public HttpHeaders headers() {
        return headers;
    }

    @Override
    public ClientHttpRequest createRequest(URI requestUri, HttpMethod method) {
        HttpHeaders requestHeaders = new HttpHeaders();
        uri = requestUri;
        headers = requestHeaders;
        return new ClientHttpRequest() {
            @Override
            public ClientHttpResponse execute() {
                return response();
            }

            @Override
            public OutputStream getBody() {
                return new ByteArrayOutputStream();
            }

            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return requestUri;
            }

            @Override
            public HttpHeaders getHeaders() {
                return requestHeaders;
            }
        };
    }

    private ClientHttpResponse response() {
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        return new ClientHttpResponse() {
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
    }

    private static boolean hasConstructor(Class<?> type, Class<?>... parameterTypes) {
        try {
            type.getConstructor(parameterTypes);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    private static Method staticMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new AssertionError(type.getName() + "에 " + name + "이 없습니다");
    }

    /** 생성 클라이언트의 public 메서드는 API 메서드 하나뿐이다 */
    private static Method apiMethod(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        throw new AssertionError(type.getName() + "에 API 메서드가 없습니다");
    }
}
