package kr.go.h2spec.parser.llm.crosscheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.llm.TestDocuments;
import kr.go.h2spec.parser.llm.normalize.IrNormalizer;
import kr.go.h2spec.parser.llm.normalize.LlmMetadata;
import kr.go.h2spec.parser.llm.normalize.NormalizedOperation;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 교차검증(설계 5.3). 짝짓기와 비교는 작은 IR로, 실제 흐름은 문서로 본다.
 * rule 엔진 결과는 {@link RuleEngine.Result}를 직접 만들거나 같은 Block 목록으로 돌린다.
 */
class CrossCheckerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmMetadata LLM = new LlmMetadata("claude", "claude-opus-5-5", "medium", "extraction-v2", "2");
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
    void 짝이_없는_LLM_오퍼레이션에는_rule_엔진은_찾지_못한_오퍼레이션이라는_노트를_남긴다() {
        CrossCheckResult result = checker.check(List.of(llm("getA", "목록", ir(URL_A)), llm("getB", "상세", ir(URL_B))),
                result(rule("getA", "목록", ir(URL_A))));

        assertEquals(List.of(), notes(result.operations().get(0)));
        assertEquals(List.of("[교차검증] rule 엔진은 찾지 못한 오퍼레이션"), notes(result.operations().get(1)));
        assertTrue(result.operations().get(1).ir().get("metadata").get("manualReviewRequired").asBoolean());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void 지원_범위_밖이나_검증에서_건너뛴_LLM_오퍼레이션도_짝을_지어_rule_엔진만_찾았다고_잘못_경고하지_않는다() {
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

    @Test
    void rule_엔진이_건너뛴_오퍼레이션과_짝지으면_그_사실을_노트로_남긴다() {
        CrossCheckResult result = checker.check(List.of(llm("getA", "목록", ir(URL_A))),
                result(new RuleEngine.OperationResult("getA", "목록", null, "응답 필드 표를 찾지 못했습니다")));

        assertEquals(List.of("[교차검증] rule 엔진은 이 오퍼레이션을 건너뜀: 응답 필드 표를 찾지 못했습니다"),
                notes(result.operations().get(0)));
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
    void 주소는_끝_슬래시만_무시하고_스킴까지_글자_그대로_비교한다() {
        assertEquals(List.of(), CrossChecker.differences(ir(URL_A), ir(URL_A + "/")));
        // 제주민요처럼 서비스 URL은 http, END POINT는 https인 문서에서 갈린 주소를 놓치지 않는다
        assertEquals(List.of("주소가 rule 엔진과 다름: rule http://api.example.kr/svc/getA, llm " + URL_A),
                CrossChecker.differences(ir(URL_A), ir("http://api.example.kr/svc/getA")));
    }

    @Test
    void 응답_필드는_경로_구조가_아니라_마지막_이름으로_비교한다() {
        // rule 엔진은 표준 구조를 강제하므로 경로 구조는 비교하지 않는다
        assertEquals(List.of(), CrossChecker.differences(
                ir(URL_A, List.of(), List.of("totCnt", "data[].baseDate"), "XML", "00"),
                ir(URL_A, List.of(), List.of("response.body.totCnt", "response.body.items.item[].baseDate"), "XML", "00")));
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
                ir(URL_A, List.of(), List.of(), "JSON", "00"), ir(URL_A, List.of(), List.of(), "XML", "00")),
                "형식을 고를 수 없는 오퍼레이션은 그대로 알린다");
    }

    @Test
    void 차이가_없으면_노트를_남기지_않고_받은_오퍼레이션을_그대로_돌려준다() {
        NormalizedOperation same = llm("getA", "목록", ir(URL_A));

        CrossCheckResult result = checker.check(List.of(same), result(rule("getA", "목록", ir(URL_A))));

        assertSame(same, result.operations().get(0));
        assertFalse(same.ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void LLM_IR의_다른_필드는_바꾸지_않고_받은_IR도_고치지_않는다() {
        ObjectNode original = ir(URL_A);
        ((ArrayNode) original.get("metadata").get("reviewNotes")).add("[LLM] 확신 없는 점");
        ObjectNode before = original.deepCopy();
        NormalizedOperation operation = llm("getA", "목록", original);

        NormalizedOperation checked = checker.check(List.of(operation), result(rule("getA", "목록", ir(URL_B))))
                .operations().get(0);

        assertEquals(before, original, "받은 IR은 그대로다");
        ObjectNode expected = before.deepCopy();
        ((ArrayNode) expected.get("metadata").get("reviewNotes"))
                .add("[교차검증] 주소가 rule 엔진과 다름: rule " + URL_B + ", llm " + URL_A);
        ((ObjectNode) expected.get("metadata")).put("manualReviewRequired", true);
        assertEquals(expected, checked.ir(), "노트와 검수 표시만 바뀐다");
        assertEquals("A", checked.apiId());
        assertEquals("getA", checked.operationName());
    }

    // ---- 실제 문서 ----

    @Test
    void 신호연계_V1이_버린_주소_후보_때문에_rule_엔진과_주소가_갈리면_교차검증이_알린다() {
        CrossCheckResult result = checkDocument("its-traffic-light.hwp", "its-traffic-light.json");

        assertEquals(List.of(new CrossChecker.Pair(0, 0, CrossChecker.PairingKey.OPERATION_NAME)), result.pairs());
        List<String> notes = notes(result.operations().get(0));
        assertTrue(notes.contains("[교차검증] 주소가 rule 엔진과 다름: "
                + "rule http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList4, "
                + "llm http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList"), notes.toString());
        assertEquals(1, notes.stream().filter(note -> note.startsWith("[교차검증]")).count(), notes.toString());
    }

    @Test
    void 측정소정보는_두_엔진의_결과가_같아_교차검증_노트와_경고가_없다() {
        CrossCheckResult result = checkDocument("msrstn-info.docx", "msrstn-info.json");

        assertEquals(3, result.pairs().size());
        result.pairs().forEach(pair -> assertEquals(CrossChecker.PairingKey.TITLE, pair.key(), "두 엔진 모두 영문명이 없다"));
        result.operations().forEach(operation -> assertTrue(
                notes(operation).stream().noneMatch(note -> note.startsWith("[교차검증]")), notes(operation).toString()));
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void 제주_와이파이는_순서로_짝짓고_rule_엔진과_다른_응답_형식과_필드를_알린다() {
        CrossCheckResult result = checkDocument("jejudatahub-wifi-usage.hwp", "jejudatahub-wifi-usage.json");

        assertEquals(List.of(new CrossChecker.Pair(0, 0, CrossChecker.PairingKey.ORDER)), result.pairs(),
                "rule 엔진은 정보 표가 없어 영문명도 국문명도 없다");
        List<String> crossChecks = notes(result.operations().get(0)).stream()
                .filter(note -> note.startsWith("[교차검증]")).toList();
        assertEquals(List.of(
                "[교차검증] 요청 파라미터가 rule 엔진과 다름: rule에만 city, emd, category, gender, ageGroup",
                "[교차검증] 응답 필드가 rule 엔진과 다름: rule에만 downloadBytes, sessionTime, userCount, city, emd, category, "
                        + "gender, ageGroup",
                "[교차검증] 응답 형식이 rule 엔진과 다름: rule XML, llm JSON"), crossChecks,
                "부록 A의 픽스처는 파라미터와 필드를 일부만 적었다");
        assertTrue(result.operations().get(0).outOfScope(), "교차검증은 지원 범위 밖 판정을 바꾸지 않는다");
    }

    // ---- 도우미 ----

    private CrossCheckResult checkDocument(String document, String fixture) {
        List<Block> blocks = TestDocuments.read(document);
        String format = TestDocuments.sourceFormat(document);
        List<NormalizedOperation> llm = new IrNormalizer(LLM).normalize(
                new ExtractionValidator().validate(TestDocuments.extraction(fixture), blocks), document, format);
        return checker.check(llm, new RuleEngine().assemble(blocks, document, format));
    }

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
