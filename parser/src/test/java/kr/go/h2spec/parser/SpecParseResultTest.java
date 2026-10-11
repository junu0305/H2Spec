package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import kr.go.h2spec.parser.SpecParseResult.Engine;
import kr.go.h2spec.parser.SpecParseResult.Operation;
import kr.go.h2spec.parser.SpecParseResult.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 엔진과 상관없는 변환 결과의 계약. CLI는 apis()로 IR을 쓰고 skipped()로 경고하며,
 * 교차검증과 평가는 오퍼레이션 기록으로 두 엔진의 오퍼레이션을 짝짓는다.
 */
class SpecParseResultTest {

    private static final Engine LLM = new Engine("llm", "claude", "claude-opus-5-5", "medium", true,
            new TokenUsage(1200, 30000, 0, 800));

    @Test
    void apis는_건너뛴_오퍼레이션과_지원_범위_밖_오퍼레이션을_빼고_문서_순서로_돌려준다() {
        ParsedApi first = api("First");
        ParsedApi pathKey = api("PathKey");
        ParsedApi last = api("Last");

        SpecParseResult result = new SpecParseResult(List.of(
                new Operation("getFirst", "첫째 기능", first, null, false),
                new Operation(null, "주소 없는 기능", null, "오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다", false),
                new Operation("getPathKey", "경로 키 기능", pathKey, "인증 방식 미지원 (주소 경로의 {your_appkey})", true),
                new Operation("getLast", "마지막 기능", last, null, false)),
                List.of("오퍼레이션 2: 오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다",
                        "getPathKey: 인증 방식 미지원 (주소 경로의 {your_appkey})"),
                LLM);

        assertEquals(List.of(first, last), result.apis());
        assertEquals(4, result.operations().size(), "건너뛴 오퍼레이션도 짝을 지을 수 있게 기록에 남는다");
        assertSame(pathKey, result.operations().get(2).api(), "지원 범위 밖 오퍼레이션은 교차검증·평가용 IR을 같이 담는다");
    }

    @Test
    void 건너뛴_목록은_엔진이_준_문구와_순서를_그대로_돌려준다() {
        // rule 엔진은 IR을 조립하다 실패한 오퍼레이션(getFirst)을 문서를 다 훑은 뒤에 더한다. 기록과 순서가 달라도 바꾸지 않는다
        List<String> skipped = List.of("getSecond: 응답 필드 표를 찾지 못했습니다",
                "getFirst: Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다: http://{host}");

        SpecParseResult result = new SpecParseResult(List.of(
                new Operation("getFirst", "첫째 기능", null, "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다: http://{host}", false),
                new Operation("getSecond", "둘째 기능", null, "응답 필드 표를 찾지 못했습니다", false),
                new Operation("getThird", "셋째 기능", api("Third"), null, false)),
                skipped, Engine.RULE);

        assertEquals(skipped, result.skipped());
    }

    @Test
    void 받은_목록이_바뀌어도_결과는_그대로다() {
        List<Operation> operations = new ArrayList<>(List.of(new Operation("getFirst", "첫째 기능", api("First"), null, false)));
        List<String> skipped = new ArrayList<>(List.of("오퍼레이션 2: 응답 필드 표를 찾지 못했습니다"));

        SpecParseResult result = new SpecParseResult(operations, skipped, Engine.RULE);
        operations.clear();
        skipped.clear();

        assertEquals(1, result.operations().size());
        assertEquals(List.of("오퍼레이션 2: 응답 필드 표를 찾지 못했습니다"), result.skipped());
        assertThrows(UnsupportedOperationException.class, () -> result.skipped().add("다른 이유"));
    }

    @Test
    void 이유_없이_빠진_오퍼레이션과_이유가_붙은_채_변환된_오퍼레이션은_받지_않는다() {
        assertThrows(IllegalArgumentException.class, () -> new Operation("getTest", "기능", null, null, false),
                "IR도 건너뛴 이유도 없다");
        assertThrows(IllegalArgumentException.class, () -> new Operation("getTest", "기능", api("Test"), null, true),
                "지원 범위 밖으로 건너뛰었으면 이유가 있어야 한다");
        assertThrows(IllegalArgumentException.class, () -> new Operation("getTest", "기능", api("Test"), "이유", false),
                "변환한 오퍼레이션에는 건너뛴 이유가 없다");
    }

    @Test
    void rule_엔진은_회사와_모델이_없고_토큰을_쓰지_않는다() {
        Engine rule = Engine.RULE;

        assertEquals("rule", rule.name());
        assertNull(rule.provider());
        assertNull(rule.model());
        assertNull(rule.effort());
        assertFalse(rule.cacheUsed());
        assertEquals(new TokenUsage(0, 0, 0, 0), rule.tokenUsage());
        assertThrows(NullPointerException.class, () -> new Engine("llm", "claude", "claude-opus-5-5", "medium", false, null),
                "토큰 합계를 엔진을 가리지 않고 낼 수 있게 사용량은 늘 있다");
    }

    private ParsedApi api(String apiId) {
        return new ParsedApi(apiId, JsonNodeFactory.instance.objectNode().put("apiId", apiId));
    }
}
