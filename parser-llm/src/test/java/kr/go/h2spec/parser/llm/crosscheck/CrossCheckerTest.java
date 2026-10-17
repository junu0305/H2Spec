package kr.go.h2spec.parser.llm.crosscheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.llm.normalize.LlmMetadata;
import kr.go.h2spec.parser.llm.normalize.NormalizedOperation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 교차검증. 짝짓기와 비교를 작은 IR로 보고, rule 엔진 결과는 {@link RuleEngine.Result}를 직접 만든다.
 * 실제 문서로 돌리는 흐름은 RecordedFlowTest가 본다.
 */
class CrossCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmMetadata LLM = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v4", "3");
    private static final String URL_A = "https://api.example.kr/svc/getA";
    private static final String URL_B = "https://api.example.kr/svc/getB";

    private final CrossChecker checker = new CrossChecker();

    // ---- 짝짓기 ----

    @Test
    void 오퍼레이션은_영문_오퍼레이션명_국문_기능명_문서_순서로_짝짓고_주소로는_짝짓지_않는다() {
        List<NormalizedOperation> llm = List.of(
                llm("getA", "목록 조회", ir(URL_A)),
                llm(null, "상세 정보 조회", ir(URL_B)),
                llm(null, "", ir("https://api.example.kr/svc/getC")),
                llm(null, "", ir("https://api.example.kr/svc/getD")));
        List<RuleEngine.OperationResult> rule = List.of(
                rule(null, "상세 정보조회", ir(URL_A)),
                rule("getA", "다른 제목", ir(URL_B)),
                rule(null, null, ir("https://api.example.kr/svc/getX")),
                rule(null, null, ir("https://api.example.kr/svc/getY")));

        List<CrossChecker.Pair> pairs = CrossChecker.pair(llm, rule);

        assertEquals(List.of(
                new CrossChecker.Pair(0, 1, CrossChecker.PairingKey.OPERATION_NAME),
                new CrossChecker.Pair(1, 0, CrossChecker.PairingKey.TITLE),
                new CrossChecker.Pair(2, 2, CrossChecker.PairingKey.ORDER),
                new CrossChecker.Pair(3, 3, CrossChecker.PairingKey.ORDER)), pairs,
                "국문 기능명은 띄어쓰기를 무시하고, 주소가 같아도 이름이 다르면 짝짓지 않는다");
    }

    @Test
    void 순서로는_검증에서_건너뛴_LLM_오퍼레이션이나_영문명이_서로_다른_오퍼레이션을_짝짓지_않아_LLM이_놓친_오퍼레이션을_알린다() {
        // LLM이 공통 구간을 오퍼레이션으로 지어내 검증에서 건너뛰었고 getB는 놓쳤다
        NormalizedOperation invented = new NormalizedOperation(null, "데이터 공통 사항", null, null,
                "오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다", false);
        CrossCheckResult missed = checker.check(List.of(llm("getA", "목록", ir(URL_A)), invented),
                result(rule("getA", "목록", ir(URL_A)), rule("getB", "상세", ir(URL_B))));
        // 영문명이 서로 다른 오퍼레이션은 순서가 같아도 같은 오퍼레이션이 아니다
        CrossCheckResult different = checker.check(
                List.of(llm("getA", "목록", ir(URL_A)), llm("getC", "", ir("https://api.example.kr/svc/getC"))),
                result(rule("getA", "목록", ir(URL_A)), rule("getB", null, ir(URL_B))));

        assertEquals(List.of("rule 엔진만 찾은 오퍼레이션: getB (" + URL_B + ")"), missed.warnings());
        assertEquals(List.of("rule 엔진만 찾은 오퍼레이션: getB (" + URL_B + ")"), different.warnings());
        assertEquals(List.of("[교차검증] " + CrossChecker.NOT_FOUND_BY_RULE), notes(different.operations().get(1)),
                "getB 기준의 엉뚱한 차이 대신 rule 엔진이 찾지 못한 오퍼레이션이라고 알린다");
    }

    @Test
    void 순서로_짝짓지_않은_자리도_건너뛰어_뒤의_오퍼레이션끼리_순서가_어긋나지_않는다() {
        // 기능명이 맞지 않는 실제 오퍼레이션이 검증에서 건너뛰어졌다. 그 자리를 비우면 L2가 R1과 짝지어진다
        NormalizedOperation dropped = new NormalizedOperation(null, "가 조회", null, null, "검증을 통과한 응답 필드가 없습니다", false);
        List<RuleEngine.OperationResult> rule = List.of(rule(null, "첫 조회", ir(URL_A)), rule(null, "둘째 조회", ir(URL_B)));

        CrossCheckResult result = checker.check(List.of(dropped, llm(null, "나 조회", ir(URL_B))), result(rule.toArray(
                new RuleEngine.OperationResult[0])));

        assertEquals(List.of(new CrossChecker.Pair(1, 1, CrossChecker.PairingKey.ORDER)), result.pairs());
        assertEquals(List.of("rule 엔진만 찾은 오퍼레이션: 첫 조회 (" + URL_A + ")"), result.warnings(),
                "LLM 결과에 IR이 없는 rule 오퍼레이션은 알린다");
        assertEquals(List.of(), notes(result.operations().get(1)), "같은 자리의 오퍼레이션과 비교한다");
    }

    @Test
    void 짝이_없는_rule_엔진_오퍼레이션은_경고로_돌려준다() {
        CrossCheckResult result = checker.check(List.of(llm("getA", "목록", ir(URL_A))), result(
                rule("getA", "목록", ir(URL_A)),
                rule("getB", "상세", ir(URL_B)),
                new RuleEngine.OperationResult(null, "통계", null, "응답 필드 표를 찾지 못했습니다")));

        assertEquals(List.of(
                "rule 엔진만 찾은 오퍼레이션: getB (" + URL_B + ")",
                "rule 엔진만 찾은 오퍼레이션: 통계 (rule 엔진도 건너뜀: 응답 필드 표를 찾지 못했습니다)"), result.warnings());
    }

    @Test
    void 지원_범위_밖이나_검증에서_건너뛴_LLM_오퍼레이션도_이름으로_짝을_지어_rule_엔진만_찾았다고_잘못_경고하지_않는다() {
        NormalizedOperation outOfScope = new NormalizedOperation("getA", "목록", "A", ir(URL_A), "인증 방식 미지원", true);
        NormalizedOperation dropped = new NormalizedOperation("getB", "상세", null, null, "검증을 통과한 응답 필드가 없습니다", false);

        CrossCheckResult result = checker.check(List.of(outOfScope, dropped),
                result(rule("getA", "목록", ir("http://api.example.kr/svc/getA")), rule("getB", "상세", ir(URL_B))));

        assertEquals(List.of(), result.warnings());
        assertEquals(List.of("[교차검증] 주소가 rule 엔진과 다름: rule http://api.example.kr/svc/getA, llm " + URL_A),
                notes(result.operations().get(0)), "지원 범위 밖 IR도 비교한다");
        assertTrue(result.operations().get(0).outOfScope());
        assertSame(dropped, result.operations().get(1), "노트를 붙일 IR이 없다");
    }

    // ---- 비교 ----

    @Test
    void 주소_요청_파라미터_응답_필드_응답_형식_성공코드가_다르면_차이를_교차검증_노트로_남긴다() {
        ObjectNode llmIr = ir(URL_A, List.of("serviceKey", "pageNo", "keyword"), List.of("totCnt", "data[].baseDate"),
                "JSON", "INFO-00");
        ObjectNode ruleIr = ir(URL_B, List.of("serviceKey", "pageNo", "numOfRows"),
                List.of("response.body.totCnt", "response.body.items.item[].baseDate", "response.body.items.item[].city"),
                "XML", "00");

        CrossCheckResult result = checker.check(List.of(llm("getA", "목록", llmIr)), result(rule("getA", "목록", ruleIr)));

        assertEquals(List.of(
                "[교차검증] 주소가 rule 엔진과 다름: rule " + URL_B + ", llm " + URL_A,
                "[교차검증] 요청 파라미터가 rule 엔진과 다름: rule에만 numOfRows / llm에만 keyword",
                "[교차검증] 응답 필드가 rule 엔진과 다름: rule에만 city",
                "[교차검증] 응답 형식이 rule 엔진과 다름: rule XML, llm JSON",
                "[교차검증] 성공코드가 rule 엔진과 다름: rule 00, llm INFO-00"), notes(result.operations().get(0)));
        assertTrue(result.operations().get(0).ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void 두_형식을_다_내고_포맷_파라미터로_형식을_고르는_오퍼레이션은_형식_차이를_알리지_않는다() {
        // 생성 클라이언트가 IR의 형식을 포맷 파라미터로 골라 부르므로 rule XML, llm JSON 어느 쪽이든 동작한다
        NormalizedOperation selectable = new NormalizedOperation("getA", "목록", "A",
                ir(URL_A, List.of(), List.of(), "JSON", "00"), null, false, true);

        CrossCheckResult result = checker.check(List.of(selectable),
                result(rule("getA", "목록", ir(URL_A, List.of(), List.of(), "XML", "00"))));

        assertSame(selectable, result.operations().get(0));
        assertEquals(List.of("응답 형식이 rule 엔진과 다름: rule XML, llm JSON"), CrossChecker.differences(
                ir(URL_A, List.of(), List.of(), "JSON", "00"), ir(URL_A, List.of(), List.of(), "XML", "00"), false),
                "형식을 고를 수 없는 오퍼레이션은 그대로 알린다");
    }

    // ---- 도우미 ----

    private NormalizedOperation llm(String operationName, String title, ObjectNode ir) {
        return new NormalizedOperation(operationName, title, "A", ir, null, false);
    }

    private RuleEngine.OperationResult rule(String operationName, String title, ObjectNode ir) {
        return new RuleEngine.OperationResult(operationName, title, new ParsedApi("A", ir), null);
    }

    private RuleEngine.Result result(RuleEngine.OperationResult... operations) {
        List<ParsedApi> apis = new ArrayList<>();
        for (RuleEngine.OperationResult operation : operations) {
            if (operation.api() != null) {
                apis.add(operation.api());
            }
        }
        return new RuleEngine.Result(apis, List.of(), List.of(operations));
    }

    private ObjectNode ir(String url) {
        return ir(url, List.of("serviceKey"), List.of("response.header.resultCode"), "XML", "00");
    }

    /** 교차검증이 보는 값만 담은 IR */
    private ObjectNode ir(String url, List<String> parameters, List<String> paths, String format, String successCode) {
        ObjectNode ir = MAPPER.createObjectNode();
        ObjectNode metadata = ir.putObject("metadata");
        metadata.put("manualReviewRequired", false);
        metadata.putArray("reviewNotes");
        ObjectNode api = ir.putObject("api");
        int split = url.lastIndexOf('/', url.endsWith("/") ? url.length() - 2 : url.length());
        api.put("baseUrl", url.substring(0, split));
        api.put("endpoint", url.substring(split));
        api.put("responseFormat", format);
        ArrayNode requestParameters = api.putArray("requestParameters");
        parameters.forEach(name -> requestParameters.addObject().put("name", name));
        ArrayNode responseFields = api.putArray("responseFields");
        paths.forEach(path -> responseFields.addObject().put("path", path));
        api.putObject("errorSpec").put("successResultCode", successCode);
        return ir;
    }

    private List<String> notes(NormalizedOperation operation) {
        List<String> notes = new ArrayList<>();
        JsonNode reviewNotes = operation.ir().get("metadata").get("reviewNotes");
        reviewNotes.forEach(note -> notes.add(note.asText()));
        return notes;
    }
}
