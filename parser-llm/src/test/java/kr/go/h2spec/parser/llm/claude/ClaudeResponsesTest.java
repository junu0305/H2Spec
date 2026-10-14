package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.client.LlmException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
