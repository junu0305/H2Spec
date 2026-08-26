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

    @Test
    void 포맷_파라미터의_샘플값으로_responseFormat을_정한다() {
        // 기관마다 설명 문구가 제각각이라("xml 또는json" 등) 샘플데이터 칸의 기본값을 근거로 삼는다
        assertEquals("JSON", responseFormat(requestRow("returnType", "데이터 표출방식", "json", "xml 또는 json")));
        assertEquals("XML", responseFormat(requestRow("returnType", "데이터 표출방식", "xml", "xml 또는 json")));
    }

    @Test
    void dataType과_resultType도_포맷_판정에_쓴다() {
        // 기상청은 dataType, 기업 재무정보는 resultType을 쓴다
        assertEquals("JSON", responseFormat(requestRow("dataType", "요청자료형식", "JSON", "요청자료형식(XML/JSON)")));
        assertEquals("JSON", responseFormat(requestRow("resultType", "결과형식", "json", "결과형식(xml/json)")));
    }

    @Test
    void 포맷_파라미터가_없으면_XML이다() {
        // KCI 문서처럼 포맷 파라미터 자체가 없는 문서
        assertEquals("XML", responseFormat(requestRow("artiId", "논문물리적식별자", "ART001", "논문 식별자")));
    }

    @Test
    void 카디널리티_구분의_컨테이너_행은_응답필드에서_제외한다() {
        // items 행(항목구분 "0..n")은 하위 표를 감싸는 컨테이너일 뿐 실제 필드가 아니다
        List<String> containerRow = List.of("items", "목록", "-", "0..n", "-", "목록");
        List<String> dataRow = responseRow("stationName", "측정소명", "종로", "측정소 이름");

        JsonNode fields = assemble(List.of(), List.of(containerRow, dataRow)).get("api").get("responseFields");

        assertEquals(1, fields.size(), "컨테이너 행은 데이터 행이 아니므로 응답필드에 포함되면 안 된다");
        assertEquals("response.body.items.item[].stationName", fields.get(0).get("path").asText());
    }

    @Test
    void 포맷_파라미터를_요청파라미터에서_지우지_않는다() {
        // IR은 문서를 그대로 표현한다. 감추는 것은 생성기 몫이다
        JsonNode param = param(requestRow("returnType", "데이터 표출방식", "json", "xml 또는 json"), "returnType");

        assertEquals("string", param.get("type").asText());
    }

    @Test
    void 요청_파라미터에_있는_페이징_필드는_body_메타로_올린다() {
        // KCI는 numOfRows 대신 recordCnt를 쓴다. 실제 응답에서 body 직계 자식이다
        String path = pathOf("recordCnt",
                List.of(requestRow("recordCnt", "레코드 건수", "10", "한 페이지 결과 수")),
                List.of(responseRow("recordCnt", "레코드 건수", "10", "한 페이지 결과 수")));

        assertEquals("response.body.recordCnt", path);
    }

    @Test
    void 요청_파라미터에_없는_카운트_필드는_item에_남긴다() {
        // 오존황사의 tmCnt("황사 발생 회차")는 이름꼴만 페이징을 닮았을 뿐 실제 데이터다
        String path = pathOf("tmCnt", List.of(),
                List.of(responseRow("tmCnt", "황사발생회차", "1", "황사 발생 회차")));

        assertEquals("response.body.items.item[].tmCnt", path);
    }

    @Test
    void totalCount는_요청_파라미터에_없어도_body_메타다() {
        String path = pathOf("totalCount", List.of(),
                List.of(responseRow("totalCount", "전체 결과 수", "40", "전체 결과 수")));

        assertEquals("response.body.totalCount", path);
    }

    @Test
    void numOfRows와_pageNo는_기존대로_body_메타다() {
        List<List<String>> reqs = List.of(
                requestRow("numOfRows", "한 페이지 결과 수", "10", "한 페이지 결과 수"),
                requestRow("pageNo", "페이지 번호", "1", "페이지 번호"));
        List<List<String>> resps = List.of(
                responseRow("numOfRows", "한 페이지 결과 수", "10", "한 페이지 결과 수"),
                responseRow("pageNo", "페이지 번호", "1", "페이지 번호"));

        JsonNode fields = assemble(reqs, resps).get("api").get("responseFields");

        assertEquals("response.body.numOfRows", fields.get(0).get("path").asText());
        assertEquals("response.body.pageNo", fields.get(1).get("path").asText());
    }

    /** 응답 필드 name이 배정받은 경로를 돌려준다 */
    private String pathOf(String name, List<List<String>> requestRows, List<List<String>> responseRows) {
        JsonNode fields = assemble(requestRows, responseRows).get("api").get("responseFields");
        for (JsonNode f : fields) {
            if (f.get("path").asText().endsWith("." + name)) {
                return f.get("path").asText();
            }
        }
        fail("응답 필드 없음: " + name);
        return null;
    }

    private String responseFormat(List<String> requestRow) {
        return assemble(List.of(requestRow), List.of()).get("api").get("responseFormat").asText();
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
        return new IrAssembler().assemble("test.docx", "DOCX", INFO, requestRows, responseRows).ir();
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
