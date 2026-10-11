package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rule 엔진의 공개 진입점. 교차검증은 이 결과의 오퍼레이션 기록(영문 오퍼레이션명, 국문 기능명, 순서)으로
 * llm 엔진의 오퍼레이션과 짝을 짓는다. 블록 목록은 SpecBlockLayoutTest처럼 실제 문서의 구조를 줄여 만들었다.
 */
class RuleEngineTest {

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
    private static final String SERVICE = "http://apis.data.go.kr/1234567/TestService";

    @Test
    void 공통_사항과_서비스_URL이_함께_있으면_공통_구간은_오퍼레이션이_아니고_뒤_오퍼레이션마다_붙는다() {
        // 서비스 개요 표의 서비스 URL은 주소가 없는 구간의 마지막 후보지만, 공통 사항 구간은 그 주소로 오퍼레이션이
        // 되지 않는다. 지금 corpus에는 이런 문서가 없어 동작을 여기서 지킨다
        RuleEngine.Result result = assemble(List.of(
                heading("가. 서비스 개요"),
                table(row("서비스명(영문)", "TestService"), row("서비스 URL", "운영환경", SERVICE)),
                heading("1. 데이터 공통 사항"),
                heading("요청 메시지 명세"),
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키"),
                        row("numOfRows", "한 페이지 결과 수", "4", "0", "10", "한 페이지 결과 수")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("resultMsg", "결과메시지", "50", "1", "OK", "결과메시지")),
                heading("2. 상세기능정보"),
                table(row("상세기능명(국문)", "목록 조회"), row("오퍼레이션명(영문)", "getTestList"),
                        row("Call Back URL", "N/A")),
                heading("요청 메시지 명세"),
                table(HEADER, row("keyword", "검색어", "20", "0", "서울", "검색어")),
                heading("응답 메시지 명세"),
                table(HEADER, row("itemName", "항목명", "50", "0", "종로", "항목 이름")),
                heading("3. 상세기능정보"),
                table(row("상세기능명(국문)", "상세 조회"), row("오퍼레이션명(영문)", "getTestDetail"),
                        row("Call Back URL", "N/A")),
                heading("요청 메시지 명세"),
                table(HEADER, row("itemId", "항목 ID", "10", "1", "A1", "항목 ID"),
                        row("numOfRows", "목록 수", "4", "0", "5", "오퍼레이션이 다시 적은 정의")),
                heading("응답 메시지 명세"),
                table(HEADER, row("itemDetail", "상세", "100", "0", "상세 내용", "상세 내용"))));

        assertEquals(List.of(), result.skipped());
        assertEquals(List.of("getTestList", "getTestDetail"),
                result.operations().stream().map(RuleEngine.OperationResult::operationName).toList(),
                "공통 사항 구간은 오퍼레이션 기록도 남기지 않는다");
        assertEquals(2, result.apis().size());

        JsonNode list = api(result, 0);
        assertEquals(SERVICE + "/getTestList", list.get("baseUrl").asText() + list.get("endpoint").asText());
        assertEquals(List.of("serviceKey", "numOfRows", "keyword"), names(list.get("requestParameters"), "name"),
                "공통 파라미터를 앞에 붙인다");
        assertEquals(List.of("resultCode", "resultMsg", "itemName"), leafNames(list));
        assertEquals("response.header.resultMsg", list.get("responseFields").get(1).get("path").asText());

        JsonNode detail = api(result, 1);
        assertEquals(SERVICE + "/getTestDetail", detail.get("baseUrl").asText() + detail.get("endpoint").asText());
        assertEquals(List.of("serviceKey", "itemId", "numOfRows"), names(detail.get("requestParameters"), "name"),
                "오퍼레이션이 같은 이름을 다시 적었으면 공통 것 대신 오퍼레이션 것을 그 자리에 쓴다");
        assertEquals("오퍼레이션이 다시 적은 정의", detail.get("requestParameters").get(2).get("description").asText());
        assertEquals(List.of("resultCode", "resultMsg", "itemDetail"), leafNames(detail));

        for (ParsedApi parsed : result.apis()) {
            assertEquals(0, parsed.ir().get("metadata").get("reviewNotes").size(),
                    "서비스 URL + 오퍼레이션명으로 주소를 정했으니 서비스 URL을 그대로 쓴다는 노트가 없다: "
                            + parsed.ir().get("metadata").get("reviewNotes"));
        }
    }

    @Test
    void 오퍼레이션을_찾지_못해도_예외_없이_빈_결과를_돌려준다() {
        RuleEngine.Result empty = assemble(List.of(
                heading("개정 이력"),
                table(row("버전", "변경일"), row("1.0", "2024-01-01"))));

        assertEquals(List.of(), empty.apis());
        assertEquals(List.of(), empty.skipped());
        assertEquals(List.of(), empty.operations());

        // 기존 파서라면 "상세기능 명세를 찾지 못했습니다"로 실패할 문서도 건너뛴 이유와 함께 돌려준다
        RuleEngine.Result skippedOnly = assemble(List.of(
                heading("상세기능정보"),
                table(row("상세기능명(국문)", "목록 조회"), row("오퍼레이션명(영문)", "getTestList"),
                        row("Call Back URL", SERVICE + "/getTestList")),
                heading("요청 메시지 명세"),
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호"))));

        assertEquals(List.of(), skippedOnly.apis());
        assertEquals(List.of("getTestList: 응답 필드 표를 찾지 못했습니다"), skippedOnly.skipped());
        assertEquals(List.of(new RuleEngine.OperationResult("getTestList", "목록 조회", null, "응답 필드 표를 찾지 못했습니다")),
                skippedOnly.operations());
    }

    @Test
    void 오퍼레이션마다_영문_오퍼레이션명과_국문_기능명을_문서_순서로_담는다() {
        List<Block> blocks = new ArrayList<>();
        blocks.addAll(operation(row("상세기능명(국문)", "첫째 기능"), row("오퍼레이션명(영문)", "getFirst"),
                row("Call Back URL", SERVICE + "/getFirst")));
        blocks.addAll(operation(row("상세기능명(국문)", "주소 없는 기능"), row("Call Back URL", "N/A")));
        blocks.addAll(operation(row("상세기능명(국문)", "셋째\n기능"), row("Call Back URL", SERVICE + "/getThird")));

        RuleEngine.Result result = assemble(blocks);

        List<RuleEngine.OperationResult> operations = result.operations();
        assertEquals(3, operations.size(), "건너뛴 오퍼레이션도 짝을 지을 수 있게 남긴다");
        assertEquals("getFirst", operations.get(0).operationName());
        assertEquals("첫째 기능", operations.get(0).title());
        assertSame(result.apis().get(0), operations.get(0).api());
        assertNull(operations.get(0).skipReason());

        assertNull(operations.get(1).operationName(), "정보 표에 영문 오퍼레이션명이 없다");
        assertEquals("주소 없는 기능", operations.get(1).title());
        assertNull(operations.get(1).api());
        assertEquals("오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다", operations.get(1).skipReason());

        assertEquals("셋째 기능", operations.get(2).title(), "IR의 apiName처럼 한 줄로 잇는다");
        assertSame(result.apis().get(1), operations.get(2).api());
        assertEquals("셋째 기능", operations.get(2).api().ir().get("api").get("apiName").asText());

        assertEquals(List.of("오퍼레이션 2: 오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다"), result.skipped(),
                "건너뛴 목록은 기존 파서와 같은 문구다");
    }

    @Test
    void 게이트웨이_serviceKey는_경로_변수_다음에_넣고_노트는_겹치는_주소_노트보다_앞에_둔다() {
        // 두 번째 기능이 첫 기능의 Call Back URL을 베꼈고, 두 표 모두 serviceKey를 적지 않았다
        String copied = "https://apis.data.go.kr/1234567/TestSvc/getTestList/{areaCode}";
        String own = "https://apis.data.go.kr/1234567/TestSvc/getTestDetail/{areaCode}";
        List<Block> blocks = new ArrayList<>();
        blocks.addAll(operation(row("Call Back URL", copied)));
        blocks.addAll(operation(row("Call Back URL", copied)));
        blocks.add(heading("요청/응답 메시지 예제"));
        blocks.add(table(row("요청메시지"), row(own + "?serviceKey=KEY&pageNo=1")));

        RuleEngine.Result result = assemble(blocks);

        JsonNode detail = api(result, 1);
        assertEquals("/getTestDetail/{areaCode}", detail.get("endpoint").asText());
        assertEquals(List.of("areaCode", "serviceKey", "pageNo"), names(detail.get("requestParameters"), "name"));
        assertEquals(List.of(IrConventions.GATEWAY_SERVICE_KEY_NOTE,
                        "Call Back URL이 다른 오퍼레이션과 같아 요청 예제의 주소를 씀: " + copied),
                texts(result.apis().get(1).ir().get("metadata").get("reviewNotes")));
        assertEquals(List.of("areaCode", "serviceKey", "pageNo"), names(api(result, 0).get("requestParameters"), "name"));
    }

    /** 정보 표와 요청·응답 표를 갖춘 오퍼레이션 하나 */
    @SafeVarargs
    private List<Block> operation(List<String>... infoRows) {
        return List.of(
                heading("상세기능정보"),
                table(infoRows),
                heading("요청 메시지 명세"),
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")));
    }

    private RuleEngine.Result assemble(List<Block> blocks) {
        return new RuleEngine().assemble(blocks, "rule.hwp", "HWP");
    }

    private Block.Heading heading(String text) {
        return new Block.Heading(text);
    }

    @SafeVarargs
    private Block.Table table(List<String>... rows) {
        return new Block.Table(List.of(rows));
    }

    private List<String> row(String... cells) {
        return List.of(cells);
    }

    private JsonNode api(RuleEngine.Result result, int index) {
        assertTrue(result.apis().size() > index, "오퍼레이션 수: " + result.apis().size());
        return result.apis().get(index).ir().get("api");
    }

    private List<String> names(JsonNode nodes, String key) {
        List<String> names = new ArrayList<>();
        nodes.forEach(node -> names.add(node.get(key).asText()));
        return names;
    }

    private List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    /** 응답 필드 경로의 마지막 이름들 */
    private List<String> leafNames(JsonNode api) {
        List<String> names = new ArrayList<>();
        api.get("responseFields").forEach(field -> {
            String path = field.get("path").asText();
            names.add(path.substring(path.lastIndexOf('.') + 1));
        });
        return names;
    }
}
