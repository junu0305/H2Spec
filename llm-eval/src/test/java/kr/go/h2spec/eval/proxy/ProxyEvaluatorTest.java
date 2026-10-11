package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.Documents;
import kr.go.h2spec.eval.EngineOperation;
import kr.go.h2spec.eval.RuleEvalEngine;
import kr.go.h2spec.eval.TestPaths;
import kr.go.h2spec.eval.proxy.ExampleParser.Format;
import kr.go.h2spec.eval.proxy.ProxyReport.DocumentSignals;
import kr.go.h2spec.eval.proxy.ProxyReport.Proxy;
import kr.go.h2spec.eval.proxy.ResponseExamples.ResponseExample;
import kr.go.h2spec.eval.proxy.SuccessCodes.Candidate;
import kr.go.h2spec.eval.proxy.SuccessCodes.Source;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대리 지표 휴리스틱을 사실을 아는 corpus 문서로 확인한다. 아래 사실은 문서를 직렬화해 직접 읽고 확인한 것이다
 * (블록 번호는 DocumentSerializer의 번호와 같다).
 * <ul>
 *   <li>jejudatahub-wifi-usage: 응답 예제는 JSON뿐(T19, T31)이고 최상위 키가 totCnt·hasMore·data 셋이다.
 *       rule IR은 XML이고, 주소의 경로에 {your_appkey}가 있다(P24)</li>
 *   <li>nec-ballot-counting-place: 응답 필드 표의 결과코드 샘플은 00(T19)인데 응답 예제는 INFO-00이다(T22)</li>
 *   <li>nongsaro-eco-farming: 게이트웨이가 아닌 api.nongsaro.go.kr에 인증키 이름이 apiKey다(T22, T28)</li>
 *   <li>jeju-folksong: 게이트웨이가 아닌 www.jeju.go.kr에 인증키 이름이 authApiKey다(T13)</li>
 *   <li>kosha-certificate-check: JSON 예제의 최상위가 {"header":…,"body":…}이고 response 루트가 없다(T32)</li>
 * </ul>
 */
class ProxyEvaluatorTest {

    private final ProxyEvaluator evaluator = new ProxyEvaluator();

    @Test
    void jeju_와이파이는_JSON뿐인_루트_여럿의_응답을_XML로_내고_경로_키를_쓴다() throws Exception {
        Path document = TestPaths.corpus("jejudatahub-wifi-usage.hwp");
        List<EngineOperation> rule = RuleEvalEngine.operations(Documents.read(document), document);

        DocumentSignals signals = evaluator.evaluate(document, "jejudatahub-wifi-usage.hwp");

        assertEquals("XML", rule.get(0).ir().path("api").path("responseFormat").asText(), "rule IR은 XML이다");
        List<ResponseExample> examples = signals.responseExamples();
        assertEquals(List.of("T19", "T31"), examples.stream().map(ResponseExample::block).toList());
        examples.forEach(example -> {
            assertEquals(Format.JSON, example.format());
            assertEquals(List.of("totCnt", "hasMore", "data"), example.roots());
        });
        assertTrue(signals.flagged(Proxy.JSON_EXAMPLE_XML_IR));
        assertEquals(List.of(ProxyEvaluator.JSON_ONLY), signals.signals().get(Proxy.JSON_EXAMPLE_XML_IR).kinds());
        assertTrue(signals.flagged(Proxy.NON_STANDARD_STRUCTURE));
        assertEquals(List.of(ProxyEvaluator.ROOT_DIFFERS), signals.signals().get(Proxy.NON_STANDARD_STRUCTURE).kinds());
        assertTrue(signals.flagged(Proxy.NON_SERVICE_KEY_AUTH));
        assertEquals(List.of(AuthSignals.PATH_KEY), signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).kinds());
        assertTrue(signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).evidence()
                .contains("경로 키: 주소의 {your_appkey} (P24)"), signals.signals().toString());
        assertFalse(signals.flagged(Proxy.NON_00_SUCCESS_CODE), "성공코드가 아예 없는 문서다");
        assertEquals(List.of(), signals.successCodes());
    }

    @Test
    void 선관위_개표소는_표의_00과_다른_INFO_00을_예제에_적었다() throws Exception {
        DocumentSignals signals = evaluator.evaluate(TestPaths.corpus("nec-ballot-counting-place.hwpx"), "nec");

        assertTrue(signals.successCodes().contains(new Candidate("00", Source.FIELD_TABLE, "T19")),
                signals.successCodes().toString());
        assertTrue(signals.successCodes().contains(new Candidate("INFO-00", Source.RESPONSE_EXAMPLE, "T22")),
                signals.successCodes().toString());
        assertTrue(signals.flagged(Proxy.NON_00_SUCCESS_CODE));
        assertEquals(List.of(ProxyEvaluator.NON_NUMERIC_CODE, ProxyEvaluator.CONFLICT),
                signals.signals().get(Proxy.NON_00_SUCCESS_CODE).kinds());
        // 에러 응답 형식(ERROR-310, 게이트웨이의 OpenAPI_ServiceResponse)은 정상 코드 후보가 아니다
        assertTrue(signals.successCodes().stream().noneMatch(candidate -> candidate.code().startsWith("ERROR")));
        assertEquals(List.of("P27", "P37"), signals.examples().stream().filter(ResponseExample::error)
                .map(ResponseExample::block).toList());
        assertFalse(signals.flagged(Proxy.NON_STANDARD_STRUCTURE), "성공 예제는 표준 구조다");
        assertFalse(signals.flagged(Proxy.NON_SERVICE_KEY_AUTH));
    }

    @Test
    void 농사로는_게이트웨이가_아닌_주소에_apiKey를_쓴다() throws Exception {
        DocumentSignals signals = evaluator.evaluate(TestPaths.corpus("nongsaro-eco-farming.hwp"), "nongsaro");

        assertTrue(signals.flagged(Proxy.NON_SERVICE_KEY_AUTH));
        assertEquals(List.of(AuthSignals.OTHER_QUERY_KEY), signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).kinds());
        assertTrue(signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).evidence().contains(
                "다른 이름의 쿼리 키: [apiKey] (http://api.nongsaro.go.kr/service/evrfrndFarmng/evrfrndFarmngLst)"));
        // AJAX 샘플 소스(<link …><script …>)는 응답 예제가 아니다
        assertEquals(List.of(), signals.examples());
    }

    @Test
    void 제주_민요는_게이트웨이가_아닌_주소에_authApiKey를_쓴다() throws Exception {
        DocumentSignals signals = evaluator.evaluate(TestPaths.corpus("jeju-folksong.hwp"), "jeju-folksong");

        assertEquals(List.of(AuthSignals.OTHER_QUERY_KEY), signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).kinds());
        assertTrue(signals.signals().get(Proxy.NON_SERVICE_KEY_AUTH).evidence().get(0).contains("[authApiKey]"));
        assertEquals(List.of("jejunetApi"), signals.responseExamples().get(0).roots());
    }

    @Test
    void 안전보건공단_JSON_예제는_response_루트_없이_header와_body로_시작한다() throws Exception {
        DocumentSignals signals = evaluator.evaluate(TestPaths.corpus("kosha-certificate-check.hwp"), "kosha");

        ResponseExample example = signals.responseExamples().get(0);
        assertEquals("T32", example.block());
        assertEquals(Format.JSON, example.format());
        assertEquals(List.of("header", "body"), example.roots());
        assertEquals("00", example.resultCode());
        assertEquals(List.of("T32 JSON: 루트가 response 하나가 아님: header, body"),
                signals.signals().get(Proxy.NON_STANDARD_STRUCTURE).evidence());
        assertTrue(signals.flagged(Proxy.JSON_EXAMPLE_XML_IR), "교환 데이터 표준이 JSON인데 rule IR은 XML이다");
        assertFalse(signals.flagged(Proxy.NON_SERVICE_KEY_AUTH), "요청 표에 serviceKey가 있다");
    }

    @Test
    void 표준_문서는_아무_지표에도_걸리지_않는다() throws Exception {
        DocumentSignals signals = evaluator.evaluate(TestPaths.corpus("kdic-account-deposit.hwp"), "kdic");

        for (Proxy proxy : Proxy.values()) {
            assertFalse(signals.flagged(proxy), proxy + ": " + signals.signals().get(proxy));
        }
        assertEquals(List.of("response"), signals.responseExamples().get(0).roots());
    }

    @Test
    void corpus_전체의_대리_지표를_센다() throws Exception {
        ProxyReport report = evaluator.evaluate(TestPaths.repoRoot().resolve("parser/src/test/resources/docs/corpus"),
                line -> {
                });

        assertEquals(9, report.readDocuments());
        assertEquals(3, report.count(Proxy.JSON_EXAMPLE_XML_IR), "jejudatahub, khoa, kosha");
        assertEquals(4, report.count(Proxy.NON_STANDARD_STRUCTURE), "its, jeju-folksong, jejudatahub, kosha");
        assertEquals(3, report.count(Proxy.NON_00_SUCCESS_CODE), "khoa(0), mohw(표 0·예제 00), nec(INFO-00)");
        assertEquals(2, report.count(Proxy.NON_00_SUCCESS_CODE, ProxyEvaluator.ZERO_VARIANT));
        assertEquals(3, report.count(Proxy.NON_SERVICE_KEY_AUTH), "jejudatahub, jeju-folksong, nongsaro");
        assertEquals(2, report.count(Proxy.NON_SERVICE_KEY_AUTH, AuthSignals.OTHER_QUERY_KEY));
    }
}
