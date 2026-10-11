package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.StringReader;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Claude 응답(스트림·메시지 객체·HTTP 오류)을 회사 공통 결과로 바꾸는 규칙(설계 6.2)을 본다. */
class ClaudeResponsesTest {

    private static final String MODEL = "claude-opus-5-5";

    @Test
    void 글_조각을_이어_붙이고_thinking과_ping은_버린다() throws Exception {
        String stream = ClaudeSse.start(MODEL, 120, 0, 3000)
                .ping()
                // thinking 글이 JSON처럼 생겨도 출력이 아니다
                .thinking(0, "{\"serviceName\":\"생각 중\"}")
                .ping()
                .text(1, "{\"serviceName\":", "\"대기오염정보\",", "\"documentNotes\":[\"줄\\n바꿈\"]}")
                .stop("end_turn", 456)
                .build();

        LlmResponse response = ClaudeResponses.fromStream(new StringReader(stream));

        assertEquals("{\"serviceName\":\"대기오염정보\",\"documentNotes\":[\"줄\\n바꿈\"]}", response.json());
        assertEquals(MODEL, response.model());
        assertEquals(Finish.COMPLETE, response.finish());
    }

    @Test
    void 사용량을_Usage의_뜻에_맞춘다() throws Exception {
        String stream = ClaudeSse.start(MODEL, 120, 2500, 3000).thinking(0, "").text(1, "{}").stop("end_turn", 456).build();

        Usage usage = ClaudeResponses.fromStream(new StringReader(stream)).usage();

        // input_tokens는 이미 캐시 읽기·쓰기를 뺀 값이고, output_tokens에는 thinking이 들어 있다.
        // Claude는 thinking 토큰을 따로 알려 주지 않아 reasoningTokens는 0이다
        assertEquals(new Usage(120, 3000, 2500, 456, 0), usage);
    }

    @Test
    void message_delta의_누계_사용량이_있으면_그것으로_덮어쓴다() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String stream = ClaudeSse.start(MODEL, 120, 2500, 3000)
                .text(0, "{}")
                .messageDelta("end_turn", mapper.createObjectNode()
                        .put("input_tokens", 130).put("cache_read_input_tokens", 3000).put("output_tokens", 40))
                .messageStop()
                .build();

        Usage usage = ClaudeResponses.fromStream(new StringReader(stream)).usage();

        // 누계라 더하지 않는다. message_delta에 없는 항목(cache_creation_input_tokens)은 message_start의 값을 둔다
        assertEquals(new Usage(130, 3000, 2500, 40, 0), usage);
    }

    @ParameterizedTest
    @CsvSource({
            "end_turn, COMPLETE",
            "stop_sequence, COMPLETE",
            "max_tokens, TRUNCATED",
            "model_context_window_exceeded, TRUNCATED",
            "refusal, REFUSED"})
    void stop_reason을_끝난_방식으로_바꾼다(String stopReason, Finish finish) throws Exception {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{\"serviceName\":").stop(stopReason, 5).build();

        LlmResponse response = ClaudeResponses.fromStream(new StringReader(stream));

        assertEquals(finish, response.finish());
        // 잘림·거절도 그때까지 받은 글을 그대로 돌려준다
        assertEquals("{\"serviceName\":", response.json());
    }

    @Test
    void 거절은_글_없이도_결과로_돌려준다() throws Exception {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).stop("refusal", 0).build();

        LlmResponse response = ClaudeResponses.fromStream(new StringReader(stream));

        assertEquals(Finish.REFUSED, response.finish());
        assertEquals("", response.json());
    }

    @Test
    void 모르는_stop_reason은_완료로_보지_않고_실패로_알린다() {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{}").stop("pause_turn", 5).build();

        LlmException e = assertThrows(LlmException.class, () -> ClaudeResponses.fromStream(new StringReader(stream)));

        assertEquals(Kind.BAD_REQUEST, e.kind());
        assertTrue(e.getMessage().contains("pause_turn"), e.getMessage());
    }

    @ParameterizedTest
    @CsvSource({
            "overloaded_error, UNAVAILABLE",
            "api_error, UNAVAILABLE",
            "rate_limit_error, RATE_LIMITED",
            "authentication_error, AUTH",
            "invalid_request_error, BAD_REQUEST",
            "새로운_오류, UNAVAILABLE"})
    void 스트림_중간의_오류_이벤트를_실패_종류로_바꾼다(String errorType, Kind kind) {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{\"a\":").error(errorType, "Overloaded").build();

        LlmException e = assertThrows(LlmException.class, () -> ClaudeResponses.fromStream(new StringReader(stream)));

        assertEquals(kind, e.kind());
        assertEquals("Claude API 응답 스트림 오류 (" + errorType + "): Overloaded", e.getMessage());
    }

    @Test
    void message_stop_전에_끝난_스트림은_다시_부를_수_있는_실패다() {
        // 연결이 중간에 끊긴 경우다. message_delta까지 왔어도 message_stop이 없으면 끝난 응답이 아니다
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{}")
                .messageDelta("end_turn", new ObjectMapper().createObjectNode().put("output_tokens", 5)).build();

        LlmException e = assertThrows(LlmException.class, () -> ClaudeResponses.fromStream(new StringReader(stream)));

        assertEquals(Kind.UNAVAILABLE, e.kind());
    }

    @Test
    void message_start나_stop_reason이_없는_스트림은_다시_부를_수_있는_실패다() {
        String full = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{}").stop("end_turn", 5).build();
        String noStart = full.substring(full.indexOf("event: content_block_start"));
        String noStopReason = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{}").messageStop().build();

        assertEquals(Kind.UNAVAILABLE, assertThrows(LlmException.class,
                () -> ClaudeResponses.fromStream(new StringReader(noStart))).kind());
        assertEquals(Kind.UNAVAILABLE, assertThrows(LlmException.class,
                () -> ClaudeResponses.fromStream(new StringReader(noStopReason))).kind());
    }

    @Test
    void 해석할_수_없는_이벤트는_다시_부를_수_있는_실패다() {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).raw("content_block_delta", "{\"type\":\"content_block_del").build();

        LlmException e = assertThrows(LlmException.class, () -> ClaudeResponses.fromStream(new StringReader(stream)));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertTrue(e.getMessage().contains("content_block_delta"), e.getMessage());
    }

    @Test
    void 모르는_이벤트와_모르는_delta는_버린다() throws Exception {
        // API 버전 정책상 이벤트와 블록 종류는 앞으로 더해질 수 있다
        String stream = ClaudeSse.start(MODEL, 10, 0, 0)
                .raw("new_event", "{\"type\":\"new_event\",\"text\":\"무시\"}")
                .raw("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"text\":\"무시\"}}")
                .text(1, "{}")
                .stop("end_turn", 5)
                .build();

        assertEquals("{}", ClaudeResponses.fromStream(new StringReader(stream)).json());
    }

    @Test
    void message_stop_뒤는_읽지_않는다() throws Exception {
        String stream = ClaudeSse.start(MODEL, 10, 0, 0).text(0, "{}").stop("end_turn", 5)
                .error("overloaded_error", "읽지 않는다")
                .build();

        assertEquals(Finish.COMPLETE, ClaudeResponses.fromStream(new StringReader(stream)).finish());
    }

    @Test
    void 배치_결과의_메시지_객체도_같은_뜻으로_바꾼다() throws Exception {
        // 배치 결과의 result.message 꼴이다. 스트리밍하지 않은 응답도 같다
        String message = """
                {"id": "msg_01TEST", "type": "message", "role": "assistant", "model": "claude-opus-5-5",
                 "content": [
                   {"type": "thinking", "thinking": "", "signature": "EqQBCgIYAhIM"},
                   {"type": "text", "text": "{\\"serviceName\\":"},
                   {"type": "text", "text": "\\"대기오염정보\\"}"}
                 ],
                 "stop_reason": "max_tokens", "stop_sequence": null,
                 "usage": {"input_tokens": 120, "cache_creation_input_tokens": 2500, "cache_read_input_tokens": 3000,
                           "output_tokens": 456}}
                """;

        LlmResponse response = ClaudeResponses.fromMessage(new ObjectMapper().readTree(message));

        assertEquals(new LlmResponse("{\"serviceName\":\"대기오염정보\"}", MODEL, new Usage(120, 3000, 2500, 456, 0),
                Finish.TRUNCATED), response);
    }

    @Test
    void stop_reason이_없는_메시지_객체는_실패로_알린다() throws Exception {
        LlmException e = assertThrows(LlmException.class,
                () -> ClaudeResponses.fromMessage(new ObjectMapper().readTree("{\"content\": [], \"stop_reason\": null}")));

        assertEquals(Kind.UNAVAILABLE, e.kind());
    }

    @ParameterizedTest
    @CsvSource({
            "401, AUTH",
            "403, AUTH",
            "429, RATE_LIMITED",
            "500, UNAVAILABLE",
            "502, UNAVAILABLE",
            "503, UNAVAILABLE",
            "504, UNAVAILABLE",
            "529, UNAVAILABLE",
            "400, BAD_REQUEST",
            "404, BAD_REQUEST",
            "413, BAD_REQUEST",
            "302, BAD_REQUEST"})
    void HTTP_상태_코드를_실패_종류로_바꾼다(int status, Kind kind) {
        assertEquals(kind, ClaudeResponses.fromHttpError(status, "", null).kind());
    }

    @Test
    void 오류_본문의_종류와_메시지와_요청_ID를_메시지에_옮긴다() {
        String body = "{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"Number of request tokens has exceeded your rate limit\"},"
                + "\"request_id\":\"req_011CSHoEeqs5C35K2UUqR7Fy\"}";

        LlmException e = ClaudeResponses.fromHttpError(429, body, Duration.ofSeconds(7));

        assertEquals("Claude API 오류 (HTTP 429): rate_limit_error: Number of request tokens has exceeded your rate limit "
                + "(request_id req_011CSHoEeqs5C35K2UUqR7Fy)", e.getMessage());
        assertEquals(Optional.of(Duration.ofSeconds(7)), e.retryAfter());
    }

    @Test
    void JSON이_아닌_오류_본문은_앞부분만_한_줄로_옮긴다() {
        String html = "<html>\n<body>\n" + "Bad Gateway ".repeat(40) + "\n</body>\n</html>";

        String message = ClaudeResponses.fromHttpError(502, html, null).getMessage();

        assertTrue(message.startsWith("Claude API 오류 (HTTP 502): <html> <body> Bad Gateway"), message);
        assertFalse(message.contains("\n"), message);
        assertTrue(message.length() < 260, message);
        assertEquals("Claude API 오류 (HTTP 503)", ClaudeResponses.fromHttpError(503, "  ", null).getMessage());
    }
}
