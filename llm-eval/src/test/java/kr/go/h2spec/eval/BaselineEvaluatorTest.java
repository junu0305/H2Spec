package kr.go.h2spec.eval;

import kr.go.h2spec.eval.BaselineReport.DocumentResult;
import kr.go.h2spec.eval.BaselineReport.Totals;
import kr.go.h2spec.eval.DataSets.EvalDocument;
import kr.go.h2spec.eval.OperationPairing.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 평가. 실제 corpus 문서로 rule 엔진 기준선이 맞게 나오는지, 손으로 만든 엔진 결과로 지표 계산의 경계를 확인한다.
 */
class BaselineEvaluatorTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 11);

    @TempDir
    Path tempDir;

    @Test
    void rule_엔진은_corpus_문서의_오퍼레이션을_모두_찾지만_serviceKey가_없는_문서는_generator를_통과하지_못한다() throws Exception {
        List<EvalDocument> documents = List.of(document("kdic-account-deposit.hwp"),
                document("jeju-folksong.hwp"), document("nongsaro-eco-farming.hwp"));
        List<String> progress = new ArrayList<>();

        BaselineReport report = new BaselineEvaluator(new RuleEvalEngine(), new GeneratorCheck())
                .evaluate("A", documents, TestPaths.repoRoot(), DATE, progress::add);
        Totals totals = report.totals();

        assertEquals(4, totals.goldOperations());
        assertEquals(new Ratio(4, 4), totals.recall());
        assertEquals(new Ratio(4, 4), totals.precision());
        assertEquals(Map.of(Method.ORDER, 4), totals.pairedBy(), "지금 정답에는 이름·기능명이 없다");
        assertEquals(0, totals.wrongUrls());
        assertEquals(1.0, totals.request().f1());
        assertEquals(1.0, totals.response().f1());
        // 인증키 이름이 apiKey·authApiKey인 nongsaro 두 개와 jeju-folksong 하나가 CodeGenerator에서 멈춘다
        assertEquals(new Ratio(1, 4), totals.generator());
        assertEquals(4, totals.generatorAssumed(), "supportedByGenerator가 없어 모두 지원 범위 안으로 가정한다");
        totals.coverage().values().forEach(covered -> assertEquals(0, covered));
        assertEquals("parser/src/test/resources/docs/corpus/jeju-folksong.hwp", report.documents().get(1).document());
        assertEquals(3, progress.size());
        assertTrue(progress.get(1).contains("generator 통과 0/1"), progress.get(1));
    }

    @Test
    void 정답이_없는_문서는_엔진_결과만_남기고_지표에서_뺀다() throws Exception {
        Path document = TestPaths.repoRoot().resolve("parser/src/test/resources/docs/msrstn-info.hwp");

        BaselineReport report = evaluate(new RuleEvalEngine(), List.of(new EvalDocument(document, null)));

        DocumentResult result = report.documents().get(0);
        assertNull(result.goldOperations());
        assertEquals(3, result.operations().size());
        assertTrue(result.generator().stream().allMatch(GeneratorCheck.Outcome::passed));
        assertEquals(0, report.totals().scoredDocuments());
        assertEquals(Ratio.EMPTY, report.totals().recall());
    }

    @Test
    void 못_찾은_오퍼레이션과_정답에_없는_오퍼레이션을_재현율과_정밀도에_따로_센다() throws Exception {
        // first: getB를 못 찾았고 getA는 주소가 틀렸다. second: 정답에 없는 getD를 더 냈다
        Path first = gold("first", """
                [{"name": "getA", "url": "http://apis.data.go.kr/1234567/TestService/getA",
                  "request": ["serviceKey"], "response": ["stationName"]},
                 {"name": "getB", "url": "http://apis.data.go.kr/1234567/TestService/getB",
                  "request": ["serviceKey"], "response": ["stationName"]}]""");
        Path second = gold("second", """
                [{"name": "getC", "url": "http://apis.data.go.kr/1234567/TestService/getC",
                  "request": ["serviceKey", "pageNo"], "response": ["stationName"]}]""");
        EvalEngine engine = byDocument(Map.of(
                "first.hwp", List.of(new IrBuilder("A", "http://apis.data.go.kr/1234567/OtherService", "/getA")
                        .param("serviceKey", "query", true).field("response.body.items.item[].stationName", "string")
                        .operation("getA", null)),
                "second.hwp", List.of(IrBuilder.gateway("getC").operation("getC", null),
                        IrBuilder.gateway("getD").operation("getD", null))));

        BaselineReport report = evaluate(engine, List.of(new EvalDocument(first.resolveSibling("first.hwp"), first),
                new EvalDocument(second.resolveSibling("second.hwp"), second)));
        Totals totals = report.totals();

        assertEquals(new Ratio(2, 3), totals.recall());
        assertEquals(new Ratio(2, 3), totals.precision());
        assertEquals(1, totals.wrongUrls(), "주소가 틀려도 이름으로 짝지어 틀린 URL로 센다");
        assertEquals(Map.of(Method.NAME, 2), totals.pairedBy());
        assertEquals(new Score(2, 0, 1), totals.request(), "getC의 pageNo가 빠졌다");
        assertEquals(new Ratio(2, 3), totals.generator(), "못 찾은 getB는 generator 통과에서도 실패다");
        assertEquals(List.of(1), report.documents().get(0).missedGold());
        assertEquals(List.of(1), report.documents().get(1).extraEngine());
    }

    @Test
    void 엔진이_건너뛴_오퍼레이션도_순서_짝짓기에_넣어_뒤_오퍼레이션이_밀리지_않는다() throws Exception {
        Path gold = gold("doc", """
                [{"url": "http://apis.data.go.kr/1234567/TestService/getA", "request": ["serviceKey"],
                  "response": ["stationName"]},
                 {"url": "http://apis.data.go.kr/1234567/TestService/getB", "request": ["serviceKey"],
                  "response": ["stationName"]}]""");
        EvalEngine engine = fixed(List.of(
                new EngineOperation(null, null, null, "응답 필드 표를 찾지 못했습니다", false),
                IrBuilder.gateway("getB").operation(null, null)));

        BaselineReport report = evaluate(engine, List.of(new EvalDocument(gold.resolveSibling("doc.hwp"), gold)));
        Totals totals = report.totals();

        assertEquals(new Ratio(1, 2), totals.recall(), "짝은 지었지만 IR이 없는 첫 오퍼레이션은 못 찾은 것이다");
        assertEquals(0, totals.wrongUrls(), "두 번째 정답은 두 번째 엔진 오퍼레이션과 짝지어 주소가 맞는다");
        assertEquals(false, report.documents().get(0).pairs().get(0).extracted());
    }

    @Test
    void 지원_범위_밖_정답은_generator_분모에서_빼고_범위_판정을_잰다() throws Exception {
        Path gold = gold("doc", """
                [{"name": "getA", "url": "http://apis.data.go.kr/1234567/TestService/getA",
                  "request": ["serviceKey"], "response": ["stationName"], "supportedByGenerator": true},
                 {"name": "getB", "url": "http://apis.data.go.kr/1234567/TestService/getB",
                  "request": ["serviceKey"], "response": ["stationName"], "supportedByGenerator": false}]""");
        EvalEngine engine = fixed(List.of(IrBuilder.gateway("getA").operation("getA", null),
                IrBuilder.gateway("getB").operation("getB", null)));

        Totals totals = evaluate(engine, List.of(new EvalDocument(gold.resolveSibling("doc.hwp"), gold))).totals();

        assertEquals(new Ratio(1, 1), totals.generator());
        assertEquals(0, totals.generatorAssumed());
        assertEquals(new Ratio(1, 2), totals.details().get(DetailMetric.SCOPE),
                "범위를 판정하지 않는 엔진은 범위 밖 getB를 건너뛰지 않아 틀린다");
        assertEquals(2, totals.coverage().get(DetailMetric.SCOPE));
    }

    @Test
    void 문서를_읽지_못하면_그_문서의_정답_오퍼레이션을_모두_못_찾은_것으로_센다() throws Exception {
        Path gold = gold("doc", """
                [{"url": "http://apis.data.go.kr/1234567/TestService/getA", "request": [], "response": []}]""");
        EvalEngine broken = new EvalEngine() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public List<EngineOperation> extract(Path document) throws IOException {
                throw new IOException("암호가 걸린 문서");
            }
        };

        BaselineReport report = evaluate(broken, List.of(new EvalDocument(gold.resolveSibling("doc.hwp"), gold)));

        assertTrue(report.documents().get(0).error().contains("암호가 걸린 문서"));
        assertEquals(new Ratio(0, 1), report.totals().recall());
        assertEquals(new Ratio(0, 1), report.totals().generator());
    }

    private BaselineReport evaluate(EvalEngine engine, List<EvalDocument> documents) throws IOException {
        return new BaselineEvaluator(engine, new GeneratorCheck()).evaluate("A", documents, tempDir, DATE, line -> {
        });
    }

    private static EvalDocument document(String name) {
        Path document = TestPaths.corpus(name);
        return new EvalDocument(document, DataSets.goldFor(document));
    }

    private Path gold(String name, String operations) throws IOException {
        return Files.writeString(tempDir.resolve(name + ".expected.json"),
                "{\"source\": \"" + name + "\", \"operations\": " + operations + "}");
    }

    /** 문서 파일 이름마다 정해진 오퍼레이션을 돌려주는 엔진 */
    private static EvalEngine byDocument(Map<String, List<EngineOperation>> operations) {
        return new EvalEngine() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public List<EngineOperation> extract(Path document) {
                return operations.get(document.getFileName().toString());
            }
        };
    }

    /** 문서와 상관없이 정해진 오퍼레이션을 돌려주는 엔진 */
    private static EvalEngine fixed(List<EngineOperation> operations) {
        return new EvalEngine() {
            @Override
            public String name() {
                return "fixed";
            }

            @Override
            public List<EngineOperation> extract(Path document) {
                return operations;
            }
        };
    }
}
