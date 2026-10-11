package kr.go.h2spec.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** CLI는 실패 종류로 처리를 가르고 메시지를 그대로 보여 준다 */
class SpecParseExceptionTest {

    @Test
    void 실패_종류와_메시지를_담는다() {
        SpecParseException e = new SpecParseException(SpecParseException.Kind.FAILED, "문서에서 상세기능 명세를 찾지 못했습니다: a.hwp");

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("문서에서 상세기능 명세를 찾지 못했습니다: a.hwp", e.getMessage());
        assertNull(e.getCause());
        assertEquals(Optional.empty(), e.engine(), "rule 엔진은 알릴 자원 사용이 없다");
    }

    @Test
    void LLM이_답한_뒤의_실패는_그때까지_쓴_엔진_정보를_담는다() {
        SpecParseResult.Engine used = new SpecParseResult.Engine("llm", "claude", "claude-opus-5-5", "medium", false,
                new SpecParseResult.TokenUsage(1_000, 0, 0, 64_000));

        SpecParseException e = new SpecParseException(SpecParseException.Kind.FAILED, "LLM 출력이 잘렸습니다", null, used);

        assertEquals(Optional.of(used), e.engine());
        assertEquals(Optional.empty(),
                new SpecParseException(SpecParseException.Kind.AUTH, "API 키가 올바르지 않습니다", null).engine());
    }

    @Test
    void 원인_예외를_함께_담는다() {
        IOException cause = new IOException("401 Unauthorized");

        SpecParseException e = new SpecParseException(SpecParseException.Kind.AUTH, "API 키가 올바르지 않습니다", cause);

        assertEquals(SpecParseException.Kind.AUTH, e.kind());
        assertSame(cause, e.getCause());
    }

    @Test
    void 실패_종류가_없으면_만들지_않는다() {
        assertThrows(NullPointerException.class, () -> new SpecParseException(null, "종류 없음"));
    }
}
