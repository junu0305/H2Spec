package kr.go.h2spec.parser.llm.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.go.h2spec.parser.llm.claude.ClaudeResponses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 회사와 상관없는 HTTP 부분을 내 PC 안의 HTTP 서버로 본다. 재시도 대기는 기록만 하고, 본문 멈춤은 짧은 대기 시간으로 실제로 잰다.
 */
class StreamingHttpTest {

    private static final String KEY = "sk-ant-test-0123456789";

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger calls = new AtomicInteger();
    private final List<Duration> sleeps = new ArrayList<>();
    private ExecutorService executor;
    private HttpServer server;

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void 본문이_멈추면_끊고_회사가_끝까지_생성해_과금했을_수_있어_다시_부르지_않는다() throws Exception {
        URI uri = serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream body = exchange.getResponseBody();
            body.write("event: ping\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            body.flush();
            await();
        });

        LlmException e = assertThrows(LlmException.class, () -> send(uri, Duration.ofMillis(300)));

        assertEquals(LlmException.Kind.INTERRUPTED, e.kind());
        assertEquals("Test API 응답 스트림이 300ms 동안 멈춰 연결을 끊었습니다", e.getMessage());
        assertEquals(1, calls.get());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void 응답_머리가_오지_않아도_끊고_다시_부르지_않는다() throws Exception {
        URI uri = serve(exchange -> await());

        LlmException e = assertThrows(LlmException.class, () -> send(uri, Duration.ofMillis(300)));

        assertEquals(LlmException.Kind.INTERRUPTED, e.kind());
        assertEquals("Test API가 300ms 안에 응답하지 않아 연결을 끊었습니다", e.getMessage());
        assertEquals(1, calls.get());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void 서버_오류는_기다렸다_다시_부르고_오류_글에_섞인_키는_가린다() throws Exception {
        URI uri = serve(exchange -> {
            byte[] body = ("{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"x-api-key " + KEY
                    + " 과부하\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(529, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        LlmException e = assertThrows(LlmException.class, () -> send(uri, Duration.ofSeconds(10)));

        assertEquals(LlmException.Kind.UNAVAILABLE, e.kind());
        assertEquals(3, calls.get(), "첫 호출 뒤 두 번 더 부른다");
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
        assertTrue(e.getMessage().contains("x-api-key **** 과부하"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY), e.getMessage());
        assertFalse(e.getCause().getMessage().contains(KEY), "원인 사슬에도 키가 남지 않는다");
    }

    @Test
    void 본문을_읽다가_연결이_끊기면_다시_부르지_않는다() throws Exception {
        URI uri = serve(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("event: ping\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });

        LlmException e = assertThrows(LlmException.class, () -> send(uri, Duration.ofSeconds(10), body -> {
            body.transferTo(Writer.nullWriter());
            throw new IOException("Connection reset");
        }));

        assertEquals(LlmException.Kind.INTERRUPTED, e.kind());
        assertEquals("Test API 응답을 읽다가 연결이 끊겼습니다: Connection reset", e.getMessage());
        assertEquals(1, calls.get());
    }

    @Test
    void 회사가_알려_준_대기_시간이_상한보다_길면_일찍_다시_부르지_않고_짧으면_그만큼_기다린다() throws Exception {
        AtomicInteger seconds = new AtomicInteger(120);
        URI uri = serve(exchange -> {
            byte[] body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"limit\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("retry-after", String.valueOf(seconds.get()));
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        LlmException longWait = assertThrows(LlmException.class, () -> send(uri, Duration.ofSeconds(10)));

        assertEquals(LlmException.Kind.RATE_LIMITED, longWait.kind());
        assertEquals(1, calls.get());
        assertEquals(List.of(), sleeps);
        assertEquals("Claude API 오류 (HTTP 429): rate_limit_error: limit (회사가 120초 뒤에 다시 부르라고 해 기다리지 않았습니다)",
                longWait.getMessage());

        seconds.set(3);
        calls.set(0);
        LlmException shortWait = assertThrows(LlmException.class, () -> send(uri, Duration.ofSeconds(10)));

        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(3), Duration.ofSeconds(3)), sleeps);
        assertEquals("Claude API 오류 (HTTP 429): rate_limit_error: limit (3번 시도)", shortWait.getMessage());
    }

    private LlmResponse send(URI uri, Duration timeout) throws LlmException {
        return send(uri, timeout, body -> {
            body.transferTo(Writer.nullWriter());
            throw new LlmException(LlmException.Kind.INTERRUPTED, "끝나는 이벤트 없이 끝났습니다");
        });
    }

    private LlmResponse send(URI uri, Duration timeout, StreamingHttp.StreamParser parser) throws LlmException {
        LlmSettings settings = LlmSettings.of("test-model", "medium", KEY).withRequestTimeout(timeout).withRetryAttempts(2);
        StreamingHttp http = new StreamingHttp("Test API", settings, HttpClient.newHttpClient(),
                new RetryPolicy(2, RetryPolicy.DEFAULT_INITIAL_DELAY, RetryPolicy.DEFAULT_MAX_DELAY, sleeps::add),
                IdleTimeoutInputStream.Timer.SYSTEM);
        return http.send(http.post(uri, new ObjectMapper().createObjectNode()).build(), parser, ClaudeResponses::fromHttpError);
    }

    private URI serve(Handler handler) throws IOException {
        executor = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            handler.handle(exchange);
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/test");
    }

    private void await() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
