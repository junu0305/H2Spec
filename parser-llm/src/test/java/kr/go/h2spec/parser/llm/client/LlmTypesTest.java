package kr.go.h2spec.parser.llm.client;

import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 회사 공통 타입(설계 6.1)의 값 규칙을 본다. */
class LlmTypesTest {

    @Test
    void 요청은_빈_값과_1보다_작은_출력_한도를_받지_않는다() {
        assertThrows(NullPointerException.class, () -> new LlmRequest(null, "문서", "지시", "{}", 100));
        assertThrows(NullPointerException.class, () -> new LlmRequest("프롬프트", "문서", null, "{}", 100));
        assertThrows(IllegalArgumentException.class, () -> new LlmRequest("프롬프트", "문서", "지시", "{}", 0));
    }

    @Test
    void 응답은_빈_값을_받지_않는다() {
        Usage usage = new Usage(1, 2, 3, 4, 0);

        assertThrows(NullPointerException.class, () -> new LlmResponse(null, "m", usage, Finish.COMPLETE));
        assertThrows(NullPointerException.class, () -> new LlmResponse("{}", "m", null, Finish.COMPLETE));
        assertThrows(NullPointerException.class, () -> new LlmResponse("{}", "m", usage, null));
    }

    @Test
    void 호출_한도와_서버_문제만_다시_부를_수_있는_실패다() {
        EnumSet<Kind> retryable = EnumSet.noneOf(Kind.class);
        for (Kind kind : Kind.values()) {
            if (kind.retryable()) {
                retryable.add(kind);
            }
        }

        assertEquals(EnumSet.of(Kind.RATE_LIMITED, Kind.UNAVAILABLE), retryable);
    }

    @Test
    void 예외는_종류와_대기_시간과_원인을_담는다() {
        IllegalStateException cause = new IllegalStateException("원인");

        LlmException e = new LlmException(Kind.RATE_LIMITED, "한도", cause, Duration.ofSeconds(3));

        assertEquals(Kind.RATE_LIMITED, e.kind());
        assertEquals(Optional.of(Duration.ofSeconds(3)), e.retryAfter());
        assertEquals(cause, e.getCause());
        assertEquals(Optional.empty(), new LlmException(Kind.AUTH, "키").retryAfter());
        assertThrows(NullPointerException.class, () -> new LlmException(null, "종류 없음"));
    }
}
