package kr.go.h2spec.eval;

import kr.go.h2spec.eval.GoldFile.GoldField;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.GoldFile.GoldParameter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 정답 파일 읽기. 0-4에서 더하는 키는 없을 수 있고, 없는 키와 빈 값을 가른다.
 */
class GoldFileTest {

    @TempDir
    Path tempDir;

    @Test
    void 지금_corpus_정답은_url_request_response만_있고_새_키는_null이다() throws Exception {
        GoldFile gold = GoldFile.read(TestPaths.corpus("jejudatahub-wifi-usage.expected.json"));

        GoldOperation op = gold.operations().get(0);
        assertEquals("와이파이읍면동별와이파이사용량API.hwp", gold.source());
        assertEquals("https://open.jejudatahub.net/api/proxy/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}", op.url());
        assertEquals(List.of("your_appkey", "number", "limit", "searchDate", "city", "emd", "category", "gender",
                "ageGroup"), op.request());
        assertEquals(12, op.response().size());
        assertNull(op.name());
        assertNull(op.title());
        assertNull(op.successCode());
        assertNull(op.responseDetail());
        assertNull(op.supportedByGenerator());
    }

    @Test
    void 설계_9_2의_새_키를_읽는다() throws Exception {
        // 설계 9.2의 예시. 후보는 {code, source}로, 인증은 LlmAuth 꼴로도 적을 수 있다
        Path file = tempDir.resolve("jeju.expected.json");
        Files.writeString(file, """
                {"source": "a.hwp", "operations": [{
                  "name": "", "title": "읍면동별 와이파이 사용량 API",
                  "url": "https://open.jejudatahub.net/api/proxy/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}",
                  "request": ["your_appkey"], "response": ["totCnt"],
                  "httpMethod": "UNKNOWN", "responseFormat": "JSON",
                  "successCode": "", "successCodeCandidates": [{"code": "0", "source": "FIELD_TABLE"}, "00"],
                  "auth": {"kind": "PATH_KEY", "parameterName": "your_appkey"},
                  "requestDetail": [{"name": "your_appkey", "in": "path", "type": "string", "required": true},
                                    {"name": "city", "in": "query", "type": "string"}],
                  "responseDetail": [{"path": "totCnt", "type": "integer"}, {"path": "data[].baseDate"}],
                  "supportedByGenerator": false
                }]}
                """);

        GoldOperation op = GoldFile.read(file).operations().get(0);

        assertEquals("", op.name(), "문서에 영문 오퍼레이션명이 없으면 빈 문자열이다. 없는 키와 다르다");
        assertEquals("읍면동별 와이파이 사용량 API", op.title());
        assertEquals("UNKNOWN", op.httpMethod());
        assertEquals("JSON", op.responseFormat());
        assertEquals("", op.successCode(), "빈 성공코드는 문서에 성공코드가 없다는 정답이다");
        assertEquals(List.of("0", "00"), op.successCodeCandidates());
        assertEquals("PATH_KEY", op.auth());
        assertEquals(List.of(new GoldParameter("your_appkey", "path", "string", true),
                new GoldParameter("city", "query", "string", null)), op.requestDetail());
        assertEquals(List.of(new GoldField("totCnt", "integer"), new GoldField("data[].baseDate", null)),
                op.responseDetail());
        assertEquals(false, op.supportedByGenerator());
    }

    @Test
    void 문서별_정답_파일을_공용_정답_파일보다_먼저_찾는다() throws Exception {
        // msrstn-info의 HWP판은 응답 필드를 줄인 축소본이라 DOCX판과 정답이 다를 수 있다
        Path docx = Files.createFile(tempDir.resolve("msrstn-info.docx"));
        Path hwp = Files.createFile(tempDir.resolve("msrstn-info.hwp"));
        Path shared = Files.writeString(tempDir.resolve("msrstn-info.expected.json"), "{}");
        Path perFormat = Files.writeString(tempDir.resolve("msrstn-info.hwp.expected.json"), "{}");
        Path alone = Files.createFile(tempDir.resolve("other.hwpx"));

        assertEquals(shared, DataSets.goldFor(docx));
        assertEquals(perFormat, DataSets.goldFor(hwp));
        assertNull(DataSets.goldFor(alone));
    }
}
