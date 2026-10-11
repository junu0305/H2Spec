package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.eval.BaselineReport.DocumentResult;
import kr.go.h2spec.eval.BaselineReport.PairResult;
import kr.go.h2spec.eval.GeneratorCheck.Outcome;
import kr.go.h2spec.eval.GeneratorCheck.Stage;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.OperationPairing.Match;
import kr.go.h2spec.eval.OperationPairing.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * summary.md(한국어 요약표)와 문서별 차이 JSON. 요약표는 PR 본문에 그대로 붙이므로 표 모양까지 본다.
 */
class BaselineReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void 요약표에_지표와_문서별_결과와_실패_이유를_쓴다() throws Exception {
        List<Path> written = new BaselineReportWriter().write(sampleReport(), tempDir);

        String summary = Files.readString(tempDir.resolve("summary.md"));
        assertEquals(tempDir.resolve("summary.md"), written.get(0));
        assertTrue(summary.startsWith("# rule 엔진 기준선 — 세트 A\n"), summary);
        assertTrue(summary.contains("- 날짜: 2026-10-11"), summary);
        assertTrue(summary.contains("| 오퍼레이션 재현율 | 50.0% (1/2) | 짝짓기 근거: 이름 0, 기능명 0, 순서 1 |"), summary);
        assertTrue(summary.contains("| 틀린 URL 수 | 1 | 주소를 비교한 짝 1개 중."), summary);
        assertTrue(summary.contains("| 요청 파라미터 이름 F1 | 0.667 | 정밀도 1.000 · 재현율 0.500 (맞음 1, 엔진에만 0, 정답에만 1) |"),
                summary);
        assertTrue(summary.contains("| generator 통과율 | 0.0% (0/2) |"), summary);
        assertTrue(summary.contains("supportedByGenerator가 없는 2개는 지원 범위 안으로 가정"), summary);
        assertTrue(summary.contains("| 응답 경로 정확도 | 정답 없음 | responseDetail 키가 있는 정답 오퍼레이션 0/2 |"), summary);
        assertTrue(summary.contains("| jeju-folksong.hwp | 2 | 1 (1) | 1/2 | 1 | 0.667 | 1.000 | 0/1 |"), summary);
        assertTrue(summary.contains("| msrstn-info.hwp | 없음 | 1 (1) | - | - | - | - | 1/1 (참고) |"), summary);
        assertTrue(summary.contains("- jeju-folksong.hwp #1: 정답 `https://www.jeju.go.kr/rest/JejuFolksongService/getJejuFolksongList`, "
                + "IR `http://www.jeju.go.kr/rest/JejuFolksongService/getJejuFolksongList`"), summary);
        assertTrue(summary.contains("- jeju-folksong.hwp #2: getOther"), summary);
        assertTrue(summary.contains("| jeju-folksong.hwp | #1 JejuFolksongList | CodeGenerator | "
                + "requestParameters에 serviceKey 파라미터가 없습니다. |"), summary);
        assertTrue(summary.contains("## 정답 파일이 없는 문서"), summary);
        assertTrue(summary.contains("- parser/src/test/resources/docs/msrstn-info.hwp"), summary);
    }

    @Test
    void llm_엔진의_요약표는_기준선이_아니라_평가로_적고_재지_않은_지표를_알린다() {
        BaselineReport rule = sampleReport();
        BaselineReport llm = new BaselineReport(rule.set(), "llm", rule.date(), rule.documents());

        String summary = new BaselineReportWriter().summary(llm);

        assertTrue(summary.startsWith("# llm 엔진 평가 — 세트 A\n"), summary);
        assertTrue(summary.contains("녹화 응답을 재생해 평가했으므로 안정성·비용·잘림 비율은 재지 않았다."), summary);
    }

    @Test
    void 요약표_칸의_세로_막대는_escape한다() {
        assertEquals("a \\| b c", Markdown.cell("a | b\nc"));
    }

    @Test
    void 문서마다_차이_JSON을_쓴다() throws Exception {
        new BaselineReportWriter().write(sampleReport(), tempDir);

        JsonNode json = new ObjectMapper().readTree(
                tempDir.resolve("documents").resolve("jeju-folksong.hwp.json").toFile());
        assertEquals("parser/src/test/resources/docs/corpus/jeju-folksong.hwp", json.get("document").asText());
        JsonNode operation = json.get("engineOperations").get(0);
        assertEquals("JejuFolksongList", operation.get("apiId").asText());
        assertEquals("CodeGenerator", operation.get("generator").get("stage").asText());
        // 정답 파일이 아직 담지 않은 값도 엔진끼리 견줄 수 있게 IR에서 옮긴다
        assertEquals("XML", operation.get("responseFormat").asText());
        assertEquals("00", operation.get("successResultCode").asText());
        assertEquals("[\"jejunetApi.items.item[].name\"]", operation.get("responsePaths").toString());
        assertEquals("[\"[교차검증] 주소가 rule 엔진과 다름\"]", operation.get("reviewNotes").toString());
        JsonNode pair = json.get("pairs").get(0);
        assertEquals(1, pair.get("gold").asInt());
        assertEquals("순서", pair.get("pairedBy").asText());
        assertFalse(pair.get("url").get("match").asBoolean());
        assertEquals("[\"authApiKey\"]", pair.get("request").get("missing").toString());
        assertEquals(2, json.get("missedGold").get(0).get("gold").asInt());
        assertTrue(Files.exists(tempDir.resolve("documents").resolve("msrstn-info.hwp.json")));
    }

    @Test
    void 새_키로_잰_지표는_문서별_JSON의_details에_쓴다() throws Exception {
        GoldOperation gold = GoldBuilder.gateway("getList").responseFormat("JSON").build();
        EngineOperation operation = IrBuilder.gateway("getList").operation(null, null);
        PairResult pair = BaselineEvaluator.compare(new Match(0, 0, Method.ORDER), gold, operation);
        DocumentResult result = new DocumentResult("x/doc.hwp", "x/doc.expected.json", null, List.of(operation),
                List.of(Outcome.PASSED), List.of(gold), List.of(pair), List.of(), List.of());

        JsonNode json = new BaselineReportWriter().documentJson(result);

        JsonNode format = json.get("pairs").get(0).get("details").get("responseFormat");
        assertEquals("응답 포맷 정확도", format.get("metric").asText());
        assertEquals(0, format.get("correct").asInt());
        assertEquals("[\"정답 JSON, IR XML\"]", format.get("mismatches").toString());
    }

    /**
     * jeju-folksong을 본뜬 결과: 정답 오퍼레이션 둘 중 하나만 찾았고, 찾은 것은 스킴이 틀리고 authApiKey를 빠뜨렸고
     * serviceKey가 없어 generator에서 멈췄다. 정답 없는 문서 하나를 함께 둔다.
     */
    private static BaselineReport sampleReport() {
        GoldOperation first = new GoldBuilder("https://www.jeju.go.kr/rest/JejuFolksongService/getJejuFolksongList",
                List.of("startPage", "authApiKey"), List.of("name")).build();
        GoldOperation second = new GoldBuilder("https://www.jeju.go.kr/rest/JejuFolksongService/getOther",
                List.of(), List.of()).name("getOther").build();
        EngineOperation found = new IrBuilder("JejuFolksongList", "http://www.jeju.go.kr/rest/JejuFolksongService",
                "/getJejuFolksongList").param("startPage", "query", false).field("jejunetApi.items.item[].name", "string")
                .note("[교차검증] 주소가 rule 엔진과 다름").operation("getJejuFolksongList", "제주 민요 정보 조회");
        PairResult pair = BaselineEvaluator.compare(new Match(0, 0, Method.ORDER), first, found);
        DocumentResult jeju = new DocumentResult("parser/src/test/resources/docs/corpus/jeju-folksong.hwp",
                "parser/src/test/resources/docs/corpus/jeju-folksong.expected.json", null, List.of(found),
                List.of(Outcome.failed(Stage.CODE_GENERATOR, "requestParameters에 serviceKey 파라미터가 없습니다.")),
                List.of(first, second), List.of(pair), List.of(1), List.of());
        EngineOperation msrstn = IrBuilder.gateway("getMsrstnList").operation("getMsrstnList", "측정소 목록 조회");
        DocumentResult noGold = new DocumentResult("parser/src/test/resources/docs/msrstn-info.hwp", null, null,
                List.of(msrstn), List.of(Outcome.PASSED), null, List.of(), List.of(), List.of());
        assertEquals(Map.of(), pair.checks());
        return new BaselineReport("A", "rule", LocalDate.of(2026, 10, 11), List.of(jeju, noGold));
    }
}
