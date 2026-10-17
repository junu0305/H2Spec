package kr.go.h2spec.parser.llm.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.LlmException.Kind;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * 회사 API를 스트리밍(SSE)으로 부르는 공통 HTTP 부분. 회사 SDK 없이 JDK {@link HttpClient}로 부른다.
 * <p>
 * 연결, 응답 본문의 멈춤 감시, 재시도({@link RetryPolicy}), 오류 글의 키 가리기를 회사와 상관없이 여기 한 곳에서 한다.
 * 회사마다 다른 것은 요청 머리·본문, 스트림 해석, 오류 분류뿐이라 어댑터가 넘긴다. 키를 가리는 일을 한 곳에 두어야 회사를 더할 때
 * 빠뜨리지 않는다.
 * <p>
 * 출력이 길고 추론 토큰까지 더해지면 응답이 몇 분씩 걸려, 한 번에 받으면 HTTP 타임아웃에 걸린다. 그래서 요청 타임아웃은 응답 머리를 받을
 * 때까지만 재고, 본문은 같은 시간 동안 아무것도 오지 않을 때만 끊는다.
 * <p>
 * 응답 머리를 기다리다 시간이 다 됐거나, 200 응답의 본문을 읽다가 멈추거나 끊긴 것({@link Kind#INTERRUPTED})은 다시 부르지 않는다.
 * 회사가 요청을 받아 생성하는 중이었다면 끊긴 뒤에도 끝까지 생성해 과금할 수 있어, 다시 부르면 비용이 겹친다. 스트림이 끝을 알리기 전에
 * 끝났거나 해석할 수 없는 조각이 온 것도 같은 이유로 어댑터의 스트림 해석이 {@link Kind#INTERRUPTED}로 알린다. 회사가 스트림 중간에
 * 서버 오류를 보낸 것은 회사가 스스로 생성을 멈춘 것이라 HTTP 오류와 같이 다시 부른다.
 */
public final class StreamingHttp {

    /** 200 응답의 스트림 본문을 끝까지 읽어 결과로 바꾼다 */
    @FunctionalInterface
    public interface StreamParser {

        /**
         * @param body 응답 본문
         * @return 출력, 모델, 사용량, 끝난 방식
         * @throws IOException  본문을 읽지 못했을 때
         * @throws LlmException 스트림 중간에 오류가 왔거나 해석할 수 없을 때
         */
        LlmResponse parse(Reader body) throws IOException, LlmException;
    }

    /** 200이 아닌 응답을 실패 종류로 바꾼다 */
    @FunctionalInterface
    public interface ErrorParser {

        /**
         * @param statusCode HTTP 상태 코드
         * @param body       오류 응답 본문의 앞부분. 읽지 못했으면 빈 문자열
         * @param retryAfter {@code retry-after} 머리의 대기 시간. 없으면 null
         * @return 던질 예외
         */
        LlmException parse(int statusCode, String body, Duration retryAfter);
    }

    /** 오류 응답은 메시지에 옮길 앞부분만 읽는다 */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    /**
     * 연결(TCP와 TLS 핸드셰이크)을 맺는 대기 시간. 없으면 JDK는 핸드셰이크에 답하지 않는 방화벽·프록시 앞에서 요청 대기 시간(10분·30분)을
     * 다 쓴 뒤에야 연결 실패를 알리고, 연결 실패는 다시 부르므로 문서 하나에 그 몇 배가 걸린다
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String apiName;
    private final LlmSettings settings;
    private final HttpClient http;
    private final RetryPolicy retry;
    private final IdleTimeoutInputStream.Timer timer;

    /**
     * @param apiName  메시지에 쓰는 API 이름 ({@code Claude API}). 뒤에 조사 "가·를"이 붙는다
     * @param settings 키, 대기 시간, 재시도 횟수
     */
    public StreamingHttp(String apiName, LlmSettings settings) {
        this(apiName, settings, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), RetryPolicy.of(settings),
                IdleTimeoutInputStream.Timer.SYSTEM);
    }

    /** 재시도 대기와 본문 멈춤 감시를 실제 시간 대신 기록하는 대기·손으로 돌리는 시계로 바꿔 시험할 수 있게 받는다 */
    public StreamingHttp(String apiName, LlmSettings settings, HttpClient http, RetryPolicy retry,
                         IdleTimeoutInputStream.Timer timer) {
        this.apiName = Objects.requireNonNull(apiName, "apiName");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = Objects.requireNonNull(http, "http");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * JSON 본문을 POST로 보낼 요청의 앞부분. 어댑터가 인증 머리를 더해 만든다.
     *
     * @param uri  보낼 주소
     * @param body 요청 본문. 한 번만 만들어 재시도마다 같은 바이트를 보낸다
     * @return 타임아웃, {@code content-type}, 본문을 넣은 요청 빌더
     */
    public HttpRequest.Builder post(URI uri, ObjectNode body) {
        return HttpRequest.newBuilder(uri)
                .timeout(settings.requestTimeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json(body)));
    }

    /**
     * 보내고 받는다. 다시 부르면 나을 수 있는 실패는 {@link RetryPolicy}가 다시 부른다.
     *
     * @param request 보낼 요청
     * @param parser  200 응답의 스트림 해석
     * @param errors  200이 아닌 응답의 분류
     * @return 결과
     * @throws LlmException 재시도한 뒤에도 실패했을 때. 메시지에 API 키는 없다
     */
    public LlmResponse send(HttpRequest request, StreamParser parser, ErrorParser errors) throws LlmException {
        return retry.call(() -> {
            try {
                return exchange(request, parser, errors);
            } catch (LlmException e) {
                // 재시도가 이 예외를 원인으로 감싸기 전에 가린다. 원인 사슬에도 키가 남지 않게 하기 위해서다
                throw redact(e);
            }
        });
    }

    /**
     * 기본 주소 뒤에 API 경로를 붙인다. 주소에 경로 앞부분이 있으면(프록시 등) 그 뒤에 붙인다.
     *
     * @param baseUrl 회사 기본 주소나 {@link LlmSettings#baseUrl()}
     * @param path    {@code /}로 시작하는 경로
     * @return 붙인 주소
     */
    public static URI resolve(URI baseUrl, String path) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path);
    }

    /**
     * {@code retry-after}는 초 단위다. HTTP 날짜 꼴과 읽을 수 없는 값은 버리고 {@link RetryPolicy}의 백오프로 기다린다.
     *
     * @param headers 응답 머리
     * @return 대기 시간. 없거나 읽을 수 없으면 null
     */
    public static Duration retryAfter(HttpHeaders headers) {
        return headers.firstValue("retry-after").map(value -> {
            try {
                double seconds = Double.parseDouble(value.strip());
                return seconds >= 0 && Double.isFinite(seconds) ? Duration.ofMillis(Math.round(seconds * 1000)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    private LlmResponse exchange(HttpRequest request, StreamParser parser, ErrorParser errors) throws LlmException {
        HttpResponse<InputStream> response = connect(request);
        IdleTimeoutInputStream body = IdleTimeoutInputStream.watch(response.body(), settings.requestTimeout(), timer);
        try {
            // 스트림은 200으로만 온다. 202·204 같은 다른 2xx(주소를 바꿔 쓰는 게이트웨이)를 스트림으로 읽으면 끊긴 스트림으로 잘못 알린다
            if (response.statusCode() != 200) {
                // 실패 종류는 어댑터가 상태 코드와 본문의 오류 정보로 가른다. 본문은 앞부분만 읽고, 못 읽으면 상태 코드만으로 가른다.
                // 어댑터가 본문을 잘라 메시지에 옮기면 잘린 자리에 걸친 키는 나중에 가릴 수 없어, 자르기 전에 키를 가린다
                throw errors.parse(response.statusCode(), maskApiKey(readErrorBody(body)), retryAfter(response.headers()));
            }
            return readStream(body, parser);
        } finally {
            closeQuietly(body);
        }
    }

    private LlmResponse readStream(IdleTimeoutInputStream body, StreamParser parser) throws LlmException {
        try {
            return parser.parse(new InputStreamReader(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (body.timedOut()) {
                throw stalled(e);
            }
            throw new LlmException(Kind.INTERRUPTED, apiName + " 응답을 읽다가 연결이 끊겼습니다: " + reason(e), e);
        } catch (LlmException e) {
            // 멈춤 감시가 본문을 닫아 스트림이 일찍 끝났으면, 끊긴 스트림보다 멈춤이 진짜 이유다
            if (body.timedOut()) {
                throw stalled(e);
            }
            throw e;
        }
    }

    private HttpResponse<InputStream> connect(HttpRequest request) throws LlmException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            // 연결을 맺기 전에 시간이 다 됐다. 요청을 보내지 못했으므로 다시 불러도 비용이 겹치지 않는다
            throw new LlmException(Kind.UNAVAILABLE,
                    apiName + "에 " + describe(connectTimeout()) + " 안에 연결하지 못했습니다", e);
        } catch (HttpTimeoutException e) {
            // 요청 타임아웃은 응답 머리를 받을 때까지만 잰다. 본문의 멈춤은 IdleTimeoutInputStream이 잰다.
            // 연결(TLS 포함)은 CONNECT_TIMEOUT이 따로 재므로, 여기까지 왔으면 회사가 요청을 받고 답하지 않은 것으로 본다
            throw new LlmException(Kind.INTERRUPTED,
                    apiName + "가 " + describe(settings.requestTimeout()) + " 안에 응답하지 않아 연결을 끊었습니다", e);
        } catch (IOException e) {
            throw new LlmException(Kind.UNAVAILABLE, apiName + "를 부르지 못했습니다: " + reason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(Kind.UNAVAILABLE, apiName + " 호출이 중단되었습니다", e);
        }
    }

    /** 오류 본문을 읽지 못해도 상태 코드만으로 실패 종류는 정할 수 있으므로 빈 글로 둔다 */
    private static String readErrorBody(InputStream body) {
        try {
            return new String(body.readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // 결과나 실패는 이미 정해졌으므로 닫다가 난 오류로 바꾸지 않는다. 다 읽기 전에 닫으면 남은 본문은 버려진다
    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            // 버린다
        }
    }

    /** 연결 실패를 알린 대기 시간. 클라이언트의 연결 대기 시간이 없거나 요청 대기 시간보다 길면 요청 대기 시간이 먼저 끝난다 */
    private Duration connectTimeout() {
        return http.connectTimeout().filter(timeout -> timeout.compareTo(settings.requestTimeout()) < 0)
                .orElse(settings.requestTimeout());
    }

    /** 오류 본문에 섞인 키를 가린다. 회사는 키를 돌려주지 않지만 주소를 바꿔 쓰는 프록시는 받은 머리를 그대로 돌려줄 수 있다 */
    private String maskApiKey(String body) {
        return body.replace(settings.apiKey(), LlmSettings.MASKED_API_KEY);
    }

    private LlmException stalled(Exception cause) {
        return new LlmException(Kind.INTERRUPTED,
                apiName + " 응답 스트림이 " + describe(settings.requestTimeout()) + " 동안 멈춰 연결을 끊었습니다", cause);
    }

    /**
     * 회사는 오류 글에 키를 담지 않지만, 주소를 바꿔 쓰는 프록시는 받은 머리를 그대로 돌려줄 수 있다.
     * 회사가 돌려준 글은 메시지에 옮기므로 키가 섞여 있으면 가린다.
     */
    private LlmException redact(LlmException e) {
        String message = e.getMessage();
        if (message == null || !message.contains(settings.apiKey())) {
            return e;
        }
        return new LlmException(e.kind(), message.replace(settings.apiKey(), LlmSettings.MASKED_API_KEY), e.getCause(),
                e.retryAfter().orElse(null));
    }

    private static byte[] json(ObjectNode body) {
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            // 트리 노드는 언제나 JSON으로 쓸 수 있어 일어나지 않는다
            throw new IllegalStateException(e);
        }
    }

    // 연결 거부 같은 예외는 메시지 없이 오기도 해서 그때는 예외 이름을 쓴다
    private static String reason(IOException e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String describe(Duration duration) {
        long millis = duration.toMillis();
        if (millis % 60_000 == 0) {
            return duration.toMinutes() + "분";
        }
        return millis % 1000 == 0 ? duration.toSeconds() + "초" : millis + "ms";
    }
}
