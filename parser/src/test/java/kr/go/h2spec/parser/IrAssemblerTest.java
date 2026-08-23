package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IrAssemblerTest {

    private static final Map<String, String> INFO = Map.of(
            "Call Back URL", "http://apis.data.go.kr/1234567/TestSvc/getTestList",
            "상세기능명(국문)", "테스트 조회");

    @Test
    void 식별자_파라미터는_숫자_샘플이어도_string으로_추론한다() {
        // 법인등록번호 1101111848914 는 13자리라 int 범위를 넘고, 선행 0도 소실된다
        JsonNode param = param(requestRow("crno", "법인등록번호", "1101111848914", "법인등록번호"), "crno");

        assertEquals("string", param.get("type").asText());
    }

    @Test
    void 일시_파라미터는_숫자_샘플이어도_string으로_추론한다() {
        // 기상청 중기예보 tmFc(발표시각) 201310170600 은 12자리라 int 범위를 넘는다
        JsonNode param = param(requestRow("tmFc", "발표시각", "201310170600", "발표시각을 입력"), "tmFc");

        assertEquals("string", param.get("type").asText());
    }

    @Test
    void 일자_응답필드는_숫자_샘플이어도_string으로_추론한다() {
        JsonNode field = field(responseRow("basDt", "기준일자", "20181231", "기준이 되는 일자(년월일)"), "basDt");

        assertEquals("string", field.get("type").asText());
    }

    @Test
    void 코드_응답필드는_숫자_샘플이어도_string으로_추론한다() {
        JsonNode field = field(responseRow("stationCode", "측정소코드", "111123", "측정소 코드 값"), "stationCode");

        assertEquals("string", field.get("type").asText());
    }

    @Test
    void 선행0이_붙은_샘플은_string으로_추론한다() {
        // 이름·설명에는 힌트가 없고 샘플의 선행 0만이 숫자가 아님을 알려주는 경우
        JsonNode field = field(responseRow("grade", "등급", "03", "등급 값"), "grade");

        assertEquals("string", field.get("type").asText());
    }

    @Test
    void 거리_필드는_이름이_tm이어도_숫자로_남긴다() {
        // 근접측정소 목록의 tm은 시각이 아니라 측정소까지의 거리(km)다
        JsonNode field = field(responseRow("tm", "거리", "1.1", "요청한 TM좌표와 측정소간의 거리(km 단위)"), "tm");

        assertEquals("number", field.get("type").asText());
    }

    @Test
    void 페이징_필드는_이름_규칙에도_불구하고_숫자로_추론한다() {
        // pageNo는 "No" 접미사 규칙에 걸리지만 실제로는 산술 대상이다
        JsonNode param = param(requestRow("pageNo", "페이지 번호", "1", "페이지 번호"), "pageNo");

        assertEquals("integer", param.get("type").asText());
    }

    @Test
    void 페이징_필드는_검토노트를_남기지_않는다() {
        JsonNode ir = assemble(List.of(requestRow("pageNo", "페이지 번호", "1", "페이지 번호")), List.of());

        JsonNode notes = ir.get("metadata").get("reviewNotes");
        assertEquals(0, notes.size(), "규칙으로 확정한 필드까지 노트를 남기면 노트가 신호 역할을 못 한다");
    }

    private JsonNode param(List<String> row, String name) {
        JsonNode params = assemble(List.of(row), List.of()).get("api").get("requestParameters");
        return find(params, "name", name);
    }

    private JsonNode field(List<String> row, String name) {
        JsonNode fields = assemble(List.of(), List.of(row)).get("api").get("responseFields");
        return find(fields, "path", "response.body.items.item[]." + name);
    }

    private JsonNode assemble(List<List<String>> requestRows, List<List<String>> responseRows) {
        return new IrAssembler().assemble("test.docx", INFO, requestRows, responseRows).ir();
    }

    /** 요청 표 열: 항목명(영문) / 항목명(국문) / 항목크기 / 항목구분 / 샘플데이터 / 항목설명 */
    private List<String> requestRow(String name, String korName, String sample, String description) {
        return List.of(name, korName, "20", "0", sample, description);
    }

    /** 응답 표 열: 항목명(영문) / 항목명(국문) / 항목크기 / 항목구분 / 샘플데이터 / 항목설명 */
    private List<String> responseRow(String name, String korName, String sample, String description) {
        return List.of(name, korName, "20", "0", sample, description);
    }

    private JsonNode find(JsonNode nodes, String key, String value) {
        for (JsonNode node : nodes) {
            if (value.equals(node.get(key).asText())) {
                return node;
            }
        }
        fail("노드 없음: " + key + "=" + value);
        return null;
    }
}
