package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 어댑터 테스트용 가짜 Claude API 서버 (설계 9.3). JDK 내장 {@link HttpServer}를 루프백 주소에 띄워 실제 네트워크로는 나가지 않는다.
 * 받은 요청을 기록하고, 미리 정한 응답을 받은 순서대로 돌려준다.
 */
final class FakeClaudeServer implements AutoCloseable {

    /** 응답 하나를 보내는 방법 */
    @FunctionalInterface
    interface Reply {
        void send(HttpExchange exchange) throws IOException, InterruptedException;
    }

    /** 받은 요청. 머리 이름은 대소문자를 가리지 않고 찾는다 */
    record Received(String method, String path, Headers headers, String body) {

        JsonNode json() throws IOException {
            return new ObjectMapper().readTree(body);
        }
    }

    private final HttpServer server;
    // 멈춘 척하는 응답이 다른 요청을 막지 않도록 요청마다 스레드를 쓴다
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Received> received = new CopyOnWriteArrayList<>();
    /** 멈춘 척하는 응답이 기다리는 신호. 서버를 닫을 때 풀어 준다 */
    private final CountDownLatch released = new CountDownLatch(1);

    FakeClaudeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    URI baseUrl() {
        InetSocketAddress address = server.getAddress();
        return URI.create("http://" + address.getHostString() + ":" + address.getPort());
    }

    /** 다음 요청들에 차례로 보낼 응답을 더한다 */
    FakeClaudeServer reply(Reply... next) {
        replies.addAll(List.of(next));
        return this;
    }

    List<Received> received() {
        return received;
    }

    /** 스트리밍 응답(SSE) 본문을 보낸다 */
    static Reply sse(String body) {
        return exchange -> {
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            write(exchange.getResponseBody(), body);
        };
    }

    /** 오류 응답을 보낸다. 머리는 이름, 값 순서로 준다 */
    static Reply error(int status, String body, String... headers) {
        return exchange -> {
            exchange.getResponseHeaders().set("content-type", "application/json");
            for (int i = 0; i < headers.length; i += 2) {
                exchange.getResponseHeaders().set(headers[i], headers[i + 1]);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        };
    }

    /** 응답 머리를 보내지 않고 멈춘다 */
    Reply stallBeforeHeaders() {
        return exchange -> released.await(30, TimeUnit.SECONDS);
    }

    /** 오류 응답의 머리와 본문 앞부분만 보내고 멈춘다 */
    Reply stallErrorBody(int status, String partialBody) {
        return exchange -> {
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(status, 0);
            OutputStream out = exchange.getResponseBody();
            write(out, partialBody);
            out.flush();
            released.await(30, TimeUnit.SECONDS);
        };
    }

    /** 스트림 앞부분만 보내고 멈춘다 */
    Reply stallAfter(String partialBody) {
        return exchange -> {
            exchange.getResponseHeaders().set("content-type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            write(out, partialBody);
            out.flush();
            released.await(30, TimeUnit.SECONDS);
        };
    }

    @Override
    public void close() {
        released.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) {
        try (exchange) {
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            Reply reply = replies.poll();
            if (reply == null) {
                error(500, "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"테스트가 정하지 않은 요청\"}}")
                        .send(exchange);
                return;
            }
            reply.send(exchange);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 클라이언트가 먼저 끊은 경우다. 시간 초과 테스트에서 일어난다
        }
    }

    private static void write(OutputStream out, String body) throws IOException {
        out.write(body.getBytes(StandardCharsets.UTF_8));
    }
}
