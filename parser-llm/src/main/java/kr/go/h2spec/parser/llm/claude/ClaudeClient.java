package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.IdleTimeoutInputStream;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.RetryPolicy;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/**
 * Claude Messages API 어댑터. 회사 SDK 없이 JDK {@link HttpClient}와 이미 쓰는 Jackson으로 부른다.
 * <p>
 * 응답은 스트리밍(SSE)으로 받는다. 출력이 길고 thinking까지 더해지면 응답이 몇 분씩 걸려, 한 번에 받으면 HTTP 타임아웃에 걸린다.
 * 429·5xx·네트워크 오류·시간 초과는 {@link RetryPolicy}로 다시 부른다. 본문은 한 번만 만들어 재시도마다 같은 바이트를 보낸다.
 * <p>
 * API 키는 요청 머리에만 싣는다. 회사가 돌려준 오류 글에 키가 섞여 와도 예외 메시지에서는 가린다.
 */
public final class ClaudeClient implements LlmClient {

    /** 기본 API 주소. {@link LlmSettings#baseUrl()}로 바꾼다 */
    public static final URI DEFAULT_BASE_URL = URI.create("https://api.anthropic.com");
    /** 요청·응답 형식의 버전 ({@code anthropic-version} 머리) */
    static final String API_VERSION = "2023-06-01";
    /** 오류 응답은 메시지에 옮길 앞부분만 읽는다 */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmSettings settings;
    private final HttpClient http;
    private final RetryPolicy retry;
    private final IdleTimeoutInputStream.Timer timer;
    private final URI messagesUri;

    /** @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소 */
    public ClaudeClient(LlmSettings settings) {
        this(settings, HttpClient.newHttpClient(), RetryPolicy.of(settings), IdleTimeoutInputStream.Timer.SYSTEM);
    }

    /** 재시도 대기와 본문 멈춤 감시를 실제 시간 대신 기록하는 대기·손으로 돌리는 시계로 바꿔 시험할 수 있게 받는다 */
    ClaudeClient(LlmSettings settings, HttpClient http, RetryPolicy retry, IdleTimeoutInputStream.Timer timer) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = Objects.requireNonNull(http, "http");
        this.retry = Objects.requireNonNull(retry, "retry");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.messagesUri = messagesUri(settings.baseUrl() == null ? DEFAULT_BASE_URL : settings.baseUrl());
    }

    /**
     * @throws IllegalArgumentException 요청의 스키마가 JSON 객체가 아닐 때. 보내기 전에 알린다
     */
    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(messagesUri)
                .timeout(settings.requestTimeout())
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", API_VERSION)
                .header("content-type", "application/json");
        if (settings.workspaceId() != null) {
            // 한 워크스페이스에 묶이지 않은 키는 이 머리가 없으면 400으로 거절된다
            builder.header("anthropic-workspace-id", settings.workspaceId());
        }
        HttpRequest httpRequest = builder
                .POST(HttpRequest.BodyPublishers.ofByteArray(json(ClaudeRequests.body(request, settings))))
                .build();
        return retry.call(() -> send(httpRequest));
    }

    /** 기본 주소 뒤에 메시지 API 경로를 붙인다. 주소에 경로 앞부분이 있으면(프록시 등) 그 뒤에 붙인다 */
    static URI messagesUri(URI baseUrl) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + "/v1/messages");
    }

    private LlmResponse send(HttpRequest request) throws LlmException {
        try {
            return exchange(request);
        } catch (LlmException e) {
            // 재시도가 이 예외를 원인으로 감싸기 전에 가린다. 원인 사슬에도 키가 남지 않게 하기 위해서다
            throw redact(e);
        }
    }

    private LlmResponse exchange(HttpRequest request) throws LlmException {
        HttpResponse<InputStream> response = connect(request);
        IdleTimeoutInputStream body = IdleTimeoutInputStream.watch(response.body(), settings.requestTimeout(), timer);
        try {
            if (response.statusCode() != 200) {
                // 실패 종류는 상태 코드가 정한다. 본문은 메시지에 옮길 앞부분만 읽고, 못 읽어도 종류는 그대로다
                throw ClaudeResponses.fromHttpError(response.statusCode(), readErrorBody(body),
                        retryAfter(response.headers()));
            }
            return readStream(body);
        } finally {
            closeQuietly(body);
        }
    }

    private LlmResponse readStream(IdleTimeoutInputStream body) throws LlmException {
        try {
            return ClaudeResponses.fromStream(new InputStreamReader(body, StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (body.timedOut()) {
                throw stalled(e);
            }
            throw new LlmException(Kind.UNAVAILABLE, "Claude API 응답을 읽다가 연결이 끊겼습니다: " + reason(e), e);
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
        } catch (HttpTimeoutException e) {
            // 요청 타임아웃은 응답 머리를 받을 때까지만 잰다. 본문의 멈춤은 IdleTimeoutInputStream이 잰다
            throw new LlmException(Kind.UNAVAILABLE,
                    "Claude API가 " + describe(settings.requestTimeout()) + " 안에 응답하지 않았습니다", e);
        } catch (IOException e) {
            throw new LlmException(Kind.UNAVAILABLE, "Claude API를 부르지 못했습니다: " + reason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException(Kind.UNAVAILABLE, "Claude API 호출이 중단되었습니다", e);
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

    private LlmException stalled(Exception cause) {
        return new LlmException(Kind.UNAVAILABLE,
                "Claude API 응답 스트림이 " + describe(settings.requestTimeout()) + " 동안 멈춰 연결을 끊었습니다", cause);
    }

    /**
     * Anthropic은 오류 글에 키를 담지 않지만, 주소를 바꿔 쓰는 프록시는 받은 머리를 그대로 돌려줄 수 있다.
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

    /**
     * {@code retry-after}는 초 단위다. HTTP 날짜 꼴과 읽을 수 없는 값은 버리고 {@link RetryPolicy}의 백오프로 기다린다.
     */
    static Duration retryAfter(HttpHeaders headers) {
        return headers.firstValue("retry-after").map(value -> {
            try {
                double seconds = Double.parseDouble(value.strip());
                return seconds >= 0 && Double.isFinite(seconds) ? Duration.ofMillis(Math.round(seconds * 1000)) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
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
