package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaudeResponsesTest {

    @Test
    void 모르는_모델의_404는_다른_문서도_같은_설정_오류로_가른다() {
        // CLI가 문서마다 같은 실패를 되풀이하지 않고 실행을 멈추게 한다
        LlmException e = ClaudeResponses.fromHttpError(404,
                "{\"type\":\"error\",\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-sonet-5-5\"}}", null);

        assertEquals(LlmException.Kind.SETTINGS, e.kind());
        assertEquals("Claude API 오류 (HTTP 404): not_found_error: model: claude-sonet-5-5", e.getMessage());
        assertEquals(LlmException.Kind.SETTINGS, ClaudeResponses.errorTypeKind("not_found_error"));
        assertEquals(LlmException.Kind.BAD_REQUEST, ClaudeResponses.statusKind(400));
    }

    @Test
    void 끝을_알리기_전에_끊긴_스트림은_다시_부르지_않는_중단이다() {
        String cut = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-5-5\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n";

        LlmException e = assertThrows(LlmException.class, () -> ClaudeResponses.fromStream(new StringReader(cut)));

        assertEquals(LlmException.Kind.INTERRUPTED, e.kind());
        assertFalse(e.kind().retryable());
        assertEquals(LlmException.Kind.INTERRUPTED, assertThrows(LlmException.class,
                () -> ClaudeResponses.fromStream(new StringReader("event: ping\ndata: {not json\n\n"))).kind(),
                "망가진 조각도 끊긴 스트림처럼 다시 부르지 않는다");
    }

    @Test
    void 잔액_부족은_기다려도_풀리지_않는_잔액_문제로_가른다() {
        LlmException e = ClaudeResponses.fromHttpError(402, "{\"type\":\"error\",\"error\":{\"type\":\"billing_error\","
                + "\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}", null);

        assertEquals(LlmException.Kind.QUOTA, e.kind());
        assertEquals(LlmException.Kind.QUOTA, ClaudeResponses.errorTypeKind("billing_error"), "스트림 중간에 와도 같다");
        assertEquals(LlmException.Kind.QUOTA, ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}", null)
                .kind(), "잔액 부족은 400으로도 온다");
        assertFalse(e.kind().retryable());
    }

    @Test
    void 모델이_받지_않는_추론_깊이의_400만_설정_오류로_가르고_다른_400은_요청_오류로_둔다() {
        LlmException effort = ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"output_config.effort: 'max' is not supported by this model\"}}", null);
        LlmException other = ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"prompt is too long: 1200000 tokens > 1000000 maximum\"}}", null);

        assertEquals(LlmException.Kind.SETTINGS, effort.kind());
        assertEquals(LlmException.Kind.BAD_REQUEST, other.kind());
        assertEquals(LlmException.Kind.BAD_REQUEST, ClaudeResponses.fromHttpError(400, "<html>bad gateway</html>", null).kind());
    }

    @Test
    void 모델의_출력_한도를_넘는_출력_상한은_설정_오류이고_입력과_합친_길이가_넘는_것은_그_문서의_요청_오류다() {
        LlmException outputLimit = ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"max_tokens: 128000 > 64000, which is the maximum allowed number of "
                + "output tokens for claude-haiku-5-5\"}}", null);
        LlmException context = ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"input length and `max_tokens` exceed context limit: "
                + "190000 + 128000 > 200000\"}}", null);

        assertEquals(LlmException.Kind.SETTINGS, outputLimit.kind(), "모델을 바꾸지 않으면 모든 문서가 같은 400을 받는다");
        assertEquals(LlmException.Kind.BAD_REQUEST, context.kind(), "작은 문서는 통과하므로 실행을 멈추지 않는다");
    }

    @Test
    void 지정한_사용_한도와_월_지출_한도에_닿은_것은_기다려도_풀리지_않는_한도_문제로_가른다() {
        LlmException usageLimit = ClaudeResponses.fromHttpError(400, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"invalid_request_error\",\"message\":\"You have reached your specified API usage limits. "
                + "You will regain access on 2026-11-01 at 00:00 UTC.\"}}", null);
        LlmException spendLimit = ClaudeResponses.fromHttpError(429, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"rate_limit_error\",\"message\":\"Monthly spend limit reached\",\"details\":{\"error_code\":"
                + "\"enforced_spend_limit_reached\"}}}", null);
        LlmException rateLimit = ClaudeResponses.fromHttpError(429, "{\"type\":\"error\",\"error\":{\"type\":"
                + "\"rate_limit_error\",\"message\":\"Number of request tokens has exceeded your per-minute rate limit\"}}", null);

        assertEquals(LlmException.Kind.QUOTA, usageLimit.kind());
        assertEquals(LlmException.Kind.QUOTA, spendLimit.kind());
        assertEquals(LlmException.Kind.RATE_LIMITED, rateLimit.kind(), "분당 한도는 곧 풀리므로 다시 부른다");
    }

    @Test
    void 공식_SDK처럼_408과_409는_다시_부른다() {
        assertEquals(LlmException.Kind.UNAVAILABLE, ClaudeResponses.statusKind(408));
        assertEquals(LlmException.Kind.UNAVAILABLE, ClaudeResponses.statusKind(409));
    }

    @Test
    void 잘림이나_거절이_아닌_끝난_이유는_출력과_토큰을_넘겨_JSON으로_판단하게_한다() throws Exception {
        String stream = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"claude-sonnet-5-5\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\","
                + "\"text\":\"{}\"}}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"pause_turn\"},"
                + "\"usage\":{\"output_tokens\":7}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";

        LlmResponse response = ClaudeResponses.fromStream(new StringReader(stream));

        assertEquals(LlmResponse.Finish.COMPLETE, response.finish(), "예외로 알리면 이미 과금된 토큰이 보고에서 빠진다");
        assertEquals("{}", response.json());
        assertEquals(7, response.usage().outputTokens());
    }
}
