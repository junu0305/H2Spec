package kr.go.h2spec.eval;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 명령행 옵션. 지금 평가할 수 있는 것은 세트 A와, rule 엔진·녹화 응답을 재생하는 llm 엔진이다. 아직 없는 것은 이유를 알리고 거부한다.
 */
class EvalOptionsTest {

    @Test
    void 옵션이_없으면_세트_A를_rule_엔진으로_평가한다() {
        EvalOptions options = EvalOptions.parse();

        assertEquals(List.of("A"), options.sets());
        assertEquals("rule", options.engine());
        assertNull(options.out());
        assertNull(options.proxy());
        assertNull(options.responses());
        assertEquals(Path.of("."), options.root());
    }

    @Test
    void llm_엔진은_녹화_응답_디렉터리와_함께_준다() {
        EvalOptions options = EvalOptions.parse("--engine", "LLM", "--responses=build/responses");

        assertEquals("llm", options.engine());
        assertEquals(Path.of("build/responses"), options.responses());
        assertEquals(List.of("A"), options.sets());
    }

    @Test
    void 값은_띄어_쓰거나_등호로_붙여_줄_수_있다() {
        EvalOptions options = EvalOptions.parse("--set=a", "--engine", "RULE", "--out", "build/x", "--root=/repo");

        assertEquals(List.of("A"), options.sets());
        assertEquals("rule", options.engine());
        assertEquals(Path.of("build/x"), options.out());
        assertEquals(Path.of("/repo"), options.root());
    }

    @Test
    void 대리_지표만_주면_정답_세트는_평가하지_않는다() {
        EvalOptions proxyOnly = EvalOptions.parse("--proxy", "docs/sample");
        EvalOptions both = EvalOptions.parse("--set", "A", "--proxy", "docs/sample");

        assertEquals(List.of(), proxyOnly.sets());
        assertEquals(Path.of("docs/sample"), proxyOnly.proxy());
        assertEquals(List.of("A"), both.sets());
    }

    @Test
    void 아직_없는_세트와_엔진은_이유를_알리고_거부한다() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--set", "A,B"))
                .getMessage().contains("0-4"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--set", "C"))
                .getMessage().contains("--proxy"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--engine", "llm"))
                .getMessage().contains("--responses"), "회사를 직접 부르는 평가는 1-10에서 붙는다");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--responses", "build/r"))
                .getMessage().contains("--engine llm"), "rule 엔진은 녹화 응답을 쓰지 않는다");
        assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--engine", "gpt"));
    }

    @Test
    void 모르는_옵션과_값이_빠진_옵션은_거부한다() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--provider", "claude"))
                .getMessage().contains("--provider"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--out"))
                .getMessage().contains("값이 없습니다"));
        assertThrows(IllegalArgumentException.class, () -> EvalOptions.parse("--out", "--set", "A"));
    }

    @Test
    void 도움말을_요청할_수_있다() {
        assertTrue(EvalOptions.parse("--help").help());
        assertTrue(EvalOptions.parse("-h").help());
    }
}
