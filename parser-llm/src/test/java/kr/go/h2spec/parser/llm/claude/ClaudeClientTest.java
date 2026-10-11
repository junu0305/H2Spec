package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.client.IdleTimeoutInputStream;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.ManualTimer;
import kr.go.h2spec.parser.llm.client.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 가짜 서버로 Claude 어댑터가 실제로 보내는 요청과, 응답·오류·시간 초과를 다루는 방식을 본다 (설계 9.3).
 * 재시도 대기는 실제로 기다리지 않고 기록만 하고, 본문 멈춤은 손으로 돌리는 시계로 만든다.
 * 실제 시간을 쓰는 것은 응답 머리가 오지 않는 경우 하나이고, 서버가 머리를 끝내 보내지 않으므로 기계가 바빠도 결과가 같다.
 */
@Timeout(30)
class ClaudeClientTest {

    private static final String API_KEY = "sk-ant-api03-SECRET-test-key_0123456789";
    private static final String MODEL = "claude-opus-5-5";
    private static final ExtractionPrompt PROMPT = ExtractionPrompt.load();
    private static final LlmRequest REQUEST = new LlmRequest(PROMPT.promptText(),
            "[P1] 요청 URL\n[P2] http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList\n",
            "문서의 모든 오퍼레이션을 문서에 나온 순서대로 뽑아라.", PROMPT.schemaText(), 32000);
    private static final String OK_STREAM = ClaudeSse.start(MODEL, 120, 2500, 0)
            .ping()
            .thinking(0, "")
            .text(1, "{\"serviceName\":", "\"대기오염정보\"}")
            .stop("end_turn", 456)
            .build();

    private final List<Duration> sleeps = new CopyOnWriteArrayList<>();
    private FakeClaudeServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new FakeClaudeServer();
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    @Test
    void 메시지_API에_키와_버전과_JSON_형식을_머리에_실어_요청_본문을_보낸다() throws Exception {
        server.reply(FakeClaudeServer.sse(OK_STREAM));

        client(0).complete(REQUEST);

        assertEquals(1, server.received().size());
        FakeClaudeServer.Received received = server.received().get(0);
        assertEquals("POST", received.method());
        assertEquals("/v1/messages", received.path());
        assertEquals(API_KEY, received.headers().getFirst("x-api-key"));
        assertEquals("2023-06-01", received.headers().getFirst("anthropic-version"));
        assertEquals("application/json", received.headers().getFirst("content-type"));
        // 본문 규칙은 ClaudeRequestsTest가 본다. 여기서는 그 본문이 그대로 나가는지 본다
        assertEquals(ClaudeRequests.body(REQUEST, settings(0)), received.json());
    }

    @Test
    void 스트리밍_응답을_공통_결과로_바꾼다() throws Exception {
        server.reply(FakeClaudeServer.sse(OK_STREAM));

        LlmResponse response = client(0).complete(REQUEST);

        assertEquals(new LlmResponse("{\"serviceName\":\"대기오염정보\"}", MODEL, new Usage(120, 0, 2500, 456, 0),
                Finish.COMPLETE), response);
    }

    @Test
    void 팩토리가_만든_클라이언트도_설정한_주소로_보낸다() throws Exception {
        server.reply(FakeClaudeServer.sse(OK_STREAM));

        LlmResponse response = new ClaudeProviderFactory().create(settings(0)).complete(REQUEST);

        assertEquals(Finish.COMPLETE, response.finish());
        assertEquals("/v1/messages", server.received().get(0).path());
    }

    @ParameterizedTest
    @CsvSource({
            "401, authentication_error, AUTH",
            "429, rate_limit_error, RATE_LIMITED",
            "529, overloaded_error, UNAVAILABLE",
            "400, invalid_request_error, BAD_REQUEST"})
    void HTTP_오류를_실패_종류로_바꾼다(int status, String errorType, Kind kind) {
        server.reply(FakeClaudeServer.error(status, errorBody(errorType, "테스트 오류")));

        LlmException e = assertThrows(LlmException.class, () -> client(0).complete(REQUEST));

        assertEquals(kind, e.kind());
        assertEquals("Claude API 오류 (HTTP " + status + "): " + errorType + ": 테스트 오류 (request_id req_test)", e.getMessage());
    }

    @Test
    void 호출_한도에_걸리면_retry_after만큼_기다렸다가_다시_부른다() throws Exception {
        server.reply(FakeClaudeServer.error(429, errorBody("rate_limit_error", "한도 초과"), "retry-after", "7"),
                FakeClaudeServer.sse(OK_STREAM));

        LlmResponse response = client(3).complete(REQUEST);

        assertEquals(Finish.COMPLETE, response.finish());
        assertEquals(2, server.received().size());
        assertEquals(List.of(Duration.ofSeconds(7)), sleeps);
        // 재시도도 같은 본문을 보낸다. 바뀌면 회사 쪽 프롬프트 캐시가 끊긴다
        assertEquals(server.received().get(0).body(), server.received().get(1).body());
    }

    @Test
    void 과부하면_지수_백오프로_기다렸다가_다시_부른다() throws Exception {
        server.reply(FakeClaudeServer.error(529, errorBody("overloaded_error", "Overloaded")),
                FakeClaudeServer.error(529, errorBody("overloaded_error", "Overloaded")),
                FakeClaudeServer.sse(OK_STREAM));

        LlmResponse response = client(3).complete(REQUEST);

        assertEquals(Finish.COMPLETE, response.finish());
        assertEquals(3, server.received().size());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
    }

    @Test
    void 스트림_중간의_과부하_오류도_다시_부른다() throws Exception {
        String overloaded = ClaudeSse.start(MODEL, 120, 0, 0).text(0, "{\"serviceName\":")
                .error("overloaded_error", "Overloaded").build();
        server.reply(FakeClaudeServer.sse(overloaded), FakeClaudeServer.sse(OK_STREAM));

        LlmResponse response = client(3).complete(REQUEST);

        assertEquals("{\"serviceName\":\"대기오염정보\"}", response.json());
        assertEquals(2, server.received().size());
    }

    @Test
    void 키_오류와_잘못된_요청은_다시_부르지_않는다() {
        server.reply(FakeClaudeServer.error(401, errorBody("authentication_error", "invalid x-api-key")),
                FakeClaudeServer.error(400, errorBody("invalid_request_error", "max_tokens: too large")));

        assertEquals(Kind.AUTH, assertThrows(LlmException.class, () -> client(3).complete(REQUEST)).kind());
        assertEquals(Kind.BAD_REQUEST, assertThrows(LlmException.class, () -> client(3).complete(REQUEST)).kind());
        assertEquals(2, server.received().size());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void 재시도를_다_쓰면_마지막_실패를_시도_횟수와_함께_던진다() {
        for (int i = 0; i < 3; i++) {
            server.reply(FakeClaudeServer.error(503, errorBody("api_error", "잠시 후 다시")));
        }

        LlmException e = assertThrows(LlmException.class, () -> client(2).complete(REQUEST));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals("Claude API 오류 (HTTP 503): api_error: 잠시 후 다시 (request_id req_test) (3번 시도)", e.getMessage());
        assertEquals(3, server.received().size());
    }

    @Test
    void 응답_머리가_대기_시간_안에_오지_않으면_다시_부를_수_있는_실패다() {
        server.reply(server.stallBeforeHeaders());
        ClaudeClient client = client(settings(0).withRequestTimeout(Duration.ofMillis(300)));

        LlmException e = assertThrows(LlmException.class, () -> client.complete(REQUEST));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals("Claude API가 300ms 안에 응답하지 않았습니다", e.getMessage());
    }

    @Test
    void 스트림이_대기_시간_동안_멈추면_끊고_다시_부를_수_있는_실패다() throws Exception {
        // 응답 머리는 바로 오므로 요청 타임아웃에는 걸리지 않는다. 본문의 멈춤은 따로 잰다
        server.reply(server.stallAfter(ClaudeSse.start(MODEL, 120, 0, 0).ping().build()));

        LlmException e = failureAfterStall(settings(0));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals("Claude API 응답 스트림이 10분 동안 멈춰 연결을 끊었습니다", e.getMessage());
    }

    @Test
    void 오류_본문이_멈춰도_실패_종류는_상태_코드가_정한다() throws Exception {
        // 멈춤(UNAVAILABLE)으로 보고 다시 부르면 틀린 키로 계속 부르게 된다
        server.reply(server.stallErrorBody(401, "{\"type\":\"error\","));

        LlmException e = failureAfterStall(settings(3));

        assertEquals(Kind.AUTH, e.kind());
        assertEquals("Claude API 오류 (HTTP 401)", e.getMessage());
        assertEquals(1, server.received().size());
    }

    @Test
    void 연결할_수_없으면_다시_부를_수_있는_실패이고_메시지에_키가_없다() throws Exception {
        URI closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = URI.create("http://127.0.0.1:" + socket.getLocalPort());
        }
        ClaudeClient client = client(settings(0).withBaseUrl(closed));

        LlmException e = assertThrows(LlmException.class, () -> client.complete(REQUEST));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().startsWith("Claude API를 부르지 못했습니다: "), e.getMessage());
        assertFalse(e.getMessage().contains(API_KEY), e.getMessage());
    }

    @Test
    void 오류_글에_키가_섞여_와도_예외_메시지와_원인에는_남지_않는다() {
        // 받은 머리를 그대로 돌려주는 프록시를 흉내 낸다. 재시도를 다 쓴 뒤 감싼 예외의 원인까지 본다
        String echoed = errorBody("authentication_error", "invalid x-api-key: " + API_KEY);
        String overloaded = errorBody("overloaded_error", "x-api-key=" + API_KEY);
        server.reply(FakeClaudeServer.error(401, echoed),
                FakeClaudeServer.error(529, overloaded), FakeClaudeServer.error(529, overloaded));

        LlmException auth = assertThrows(LlmException.class, () -> client(0).complete(REQUEST));
        LlmException exhausted = assertThrows(LlmException.class, () -> client(1).complete(REQUEST));

        assertEquals("Claude API 오류 (HTTP 401): authentication_error: invalid x-api-key: " + LlmSettings.MASKED_API_KEY
                + " (request_id req_test)", auth.getMessage());
        for (Throwable e = exhausted; e != null; e = e.getCause()) {
            assertFalse(String.valueOf(e.getMessage()).contains(API_KEY), e.getMessage());
        }
        assertInstanceOf(LlmException.class, exhausted.getCause());
    }

    @Test
    void 기본_주소는_Anthropic_API이고_경로_앞부분이_있는_주소도_받는다() {
        assertEquals(URI.create("https://api.anthropic.com/v1/messages"), ClaudeClient.messagesUri(ClaudeClient.DEFAULT_BASE_URL));
        assertEquals(URI.create("http://127.0.0.1:8080/proxy/v1/messages"),
                ClaudeClient.messagesUri(URI.create("http://127.0.0.1:8080/proxy/")));
    }

    private LlmSettings settings(int retryAttempts) {
        return LlmSettings.of(MODEL, "medium", API_KEY).withBaseUrl(server.baseUrl()).withRetryAttempts(retryAttempts);
    }

    private ClaudeClient client(int retryAttempts) {
        return client(settings(retryAttempts));
    }

    private ClaudeClient client(LlmSettings settings) {
        return client(settings, IdleTimeoutInputStream.Timer.SYSTEM);
    }

    /** 운영과 같은 HttpClient와 백오프를 쓰고, 재시도 대기는 기록만 한다 */
    private ClaudeClient client(LlmSettings settings, IdleTimeoutInputStream.Timer timer) {
        return new ClaudeClient(settings, HttpClient.newHttpClient(), new RetryPolicy(settings.retryAttempts(),
                RetryPolicy.DEFAULT_INITIAL_DELAY, RetryPolicy.DEFAULT_MAX_DELAY, sleeps::add), timer);
    }

    /**
     * 손으로 돌리는 시계로 본문 멈춤을 만든다. 감시가 시작되면(응답 머리를 받으면) 호출이 끝날 때까지 대기 시간만큼씩 시계를 돌린다.
     * 응답 머리는 기본 대기 시간(10분) 안에만 오면 되고 멈춤은 시계가 정하므로, 기계가 바빠도 결과가 같다.
     */
    private LlmException failureAfterStall(LlmSettings settings) throws Exception {
        ManualTimer timer = new ManualTimer();
        ClaudeClient client = client(settings, timer);
        CompletableFuture<LlmResponse> call = CompletableFuture.supplyAsync(() -> {
            try {
                return client.complete(REQUEST);
            } catch (LlmException e) {
                throw new CompletionException(e);
            }
        });
        timer.awaitScheduled();
        // 시계를 돌린 직후에 읽는 스레드가 남은 데이터를 읽으면 감시가 다시 걸리므로, 호출이 끝날 때까지 돌린다
        while (!call.isDone()) {
            timer.advance(settings.requestTimeout());
            try {
                call.get(50, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | ExecutionException e) {
                // 아직 읽는 중이거나 끝났다. 끝난 결과는 아래에서 본다
            }
        }
        ExecutionException e = assertThrows(ExecutionException.class, call::get);
        return assertInstanceOf(LlmException.class, e.getCause());
    }

    private static String errorBody(String type, String message) {
        return "{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"},\"request_id\":\"req_test\"}";
    }
}
