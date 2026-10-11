package kr.go.h2spec.eval.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.eval.TestPaths;
import kr.go.h2spec.eval.proxy.ProxyReport.DocumentSignals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 대리 지표의 요약표(proxy-summary.md)와 근거 JSON(proxy.json). 휴리스틱이라는 것을 두 파일에 함께 적는다.
 */
class ProxyReportWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void 요약표에_휴리스틱임을_밝히고_지표마다_문서_수와_세부_종류를_쓴다() throws Exception {
        List<Path> written = new ProxyReportWriter().write(report(), tempDir);

        String summary = Files.readString(written.get(0));
        assertEquals(tempDir.resolve("proxy-summary.md"), written.get(0));
        assertTrue(summary.startsWith("# 세트 C 대리 지표 (휴리스틱)\n\n> 원문 블록과 rule 엔진 결과를 패턴으로 비교해 센 휴리스틱이라"),
                summary);
        assertTrue(summary.contains("- 문서: 4개 (읽지 못함 1개, rule 엔진이 IR을 내지 못함 0개)"), summary);
        assertTrue(summary.contains("| (a) 응답 예제는 JSON인데 rule IR의 responseFormat이 XML | 1 | 33.3% (1/3) |"), summary);
        assertTrue(summary.contains("| └ XML 예제 없음 | 1 | |"), summary);
        assertTrue(summary.contains("| (c) 00이 아닌 정상 코드가 적힘 | 1 | 33.3% (1/3) |"), summary);
        assertTrue(summary.contains("| (d) serviceKey 쿼리가 아닌 인증 | 2 | 66.7% (2/3) |"), summary);
        assertTrue(summary.contains("| └ 경로 키 | 1 | |"), summary);
        assertTrue(summary.contains("| └ 다른 이름의 쿼리 키 | 1 | |"), summary);
        assertTrue(summary.contains("| jeju/jejudatahub-wifi-usage.hwp | O | O | | O | (a) JSON 응답 예제 [T19, T31]"), summary);
        assertTrue(summary.contains("| broken.hwp | | | | | 읽지 못함: java.io.IOException: 깨진 문서 |"), summary);
    }

    @Test
    void 근거_JSON에_문서별_응답_예제와_성공코드_후보와_판정을_쓴다() throws Exception {
        new ProxyReportWriter().write(report(), tempDir);

        JsonNode json = new ObjectMapper().readTree(tempDir.resolve("proxy.json").toFile());
        assertTrue(json.get("heuristic").asBoolean());
        assertEquals(1, json.get("counts").get("jsonExampleXmlIr").get("documents").asInt());
        assertEquals(1, json.get("counts").get("nonServiceKeyAuth").get("kinds").get("경로 키").asInt());
        JsonNode jeju = json.get("perDocument").get(0);
        assertEquals("jeju/jejudatahub-wifi-usage.hwp", jeju.get("document").asText());
        JsonNode example = jeju.get("responseExamples").get(1);
        assertEquals("T31", example.get("block").asText());
        assertEquals("JSON", example.get("format").asText());
        assertEquals("[\"totCnt\",\"hasMore\",\"data\"]", example.get("roots").toString());
        assertEquals(false, example.get("standard").asBoolean());
        assertTrue(jeju.get("signals").get("nonStandardStructure").get("flagged").asBoolean());
        JsonNode nec = json.get("perDocument").get(1);
        assertEquals("INFO-00", nec.get("successCodeCandidates").get(2).get("code").asText());
        assertEquals("RESPONSE_EXAMPLE", nec.get("successCodeCandidates").get(2).get("source").asText());
        assertEquals("java.io.IOException: 깨진 문서", json.get("perDocument").get(3).get("error").asText());
    }

    /** 하위 디렉터리의 jejudatahub, nec, jeju-folksong, 읽지 못한 문서 하나 */
    private static ProxyReport report() {
        ProxyEvaluator evaluator = new ProxyEvaluator();
        List<DocumentSignals> documents = List.of(
                evaluator.evaluate(TestPaths.corpus("jejudatahub-wifi-usage.hwp"), "jeju/jejudatahub-wifi-usage.hwp"),
                evaluator.evaluate(TestPaths.corpus("nec-ballot-counting-place.hwpx"), "nec-ballot-counting-place.hwpx"),
                evaluator.evaluate(TestPaths.corpus("jeju-folksong.hwp"), "jeju-folksong.hwp"),
                new DocumentSignals("broken.hwp", "java.io.IOException: 깨진 문서", 0, 0, List.of(), List.of(), Map.of()));
        return new ProxyReport("setC", documents);
    }
}
