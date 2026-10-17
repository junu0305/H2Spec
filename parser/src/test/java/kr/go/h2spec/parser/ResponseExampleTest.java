package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 응답 필드 표가 알려 주지 않는 성공코드·응답 구조와 요청 표에서 빠진 인증키를 문서의 응답·요청 예제에서 찾는다.
 * 블록 목록은 공공데이터포털 문서 수백 건에서 본 꼴을 최소로 줄인 것이다.
 */
class ResponseExampleTest {

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
    private static final String CALL_BACK = "http://apis.data.go.kr/1234567/TestSvc/getTestList";

    @Test
    void 정상_응답_예제의_결과코드를_성공코드로_쓰고_오류_예제는_보지_않는다() {
        // 국립암센터 등: 정상 응답이 resultCode 200을 준다. 게이트웨이 오류 예제와 오류 응답 예제가 함께 실려 있다
        JsonNode api = api(standard(fields(), example(
                "<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                        + "<returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>",
                "<response><header><resultCode>400</resultCode><resultMsg>INVALID_REQUEST</resultMsg></header></response>",
                "<response>\n<header>\n<resultCode>200</resultCode>\n<resultMsg>NORMAL SERVICE</resultMsg>\n</header>\n"
                        + "<body><items><item><name>센터</name><addr>서울</addr></item></items></body>\n</response>")));

        assertEquals("200", api.at("/errorSpec/successResultCode").asText());
        assertTrue(notes(api).contains("응답 예제의 정상 응답 결과코드 200를 성공코드로 씀"), notes(api).toString());
    }

    @Test
    void 영으로만_된_결과코드는_00으로_적는다() {
        JsonNode api = api(standard(fields(), example(
                "{\"response\": {\"header\": {\"resultCode\": 0, \"resultMsg\": \"NORMAL_SERVICE\"}}}")));

        assertEquals("00", api.at("/errorSpec/successResultCode").asText());
    }

    @Test
    void 응답_예제가_없으면_에러_코드_표의_정상_행으로_성공코드를_정한다() {
        // 선관위: 에러 코드 표는 문서 끝에 한 번만 둔다
        List<Block> blocks = standard(fields());
        blocks.add(heading("마. OpenAPI 에러 코드정리"));
        blocks.add(table(row("에러코드", "에러메시지", "설명"), row("INFO-00", "NORMAL SERVICE", "정상"),
                row("INFO-03", "NODATA_ERROR", "데이터 없음")));

        JsonNode api = api(blocks);

        assertEquals("INFO-00", api.at("/errorSpec/successResultCode").asText());
    }

    @Test
    void 응답_예제의_구조로_루트와_목록_경로를_정한다() {
        // 지자체 rfcOpenApi: 루트가 response가 아니고 목록이 body.data.list로 온다. 예제에 없는 tel도 목록 원소에 둔다
        JsonNode api = api(standard(
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("resultMsg", "결과메시지", "50", "1", "success", "결과메시지"),
                        row("totalCount", "전체 건수", "4", "1", "2", "전체 건수"),
                        row("name", "이름", "50", "0", "센터", "이름"),
                        row("addr", "주소", "50", "0", "서울", "주소"),
                        row("tel", "전화", "20", "0", "02", "전화")),
                example("<rfcOpenApi>\n<header>\n<resultCode>00</resultCode>\n<resultMsg>success</resultMsg>\n</header>\n"
                        + "<body>\n<totalCount>2</totalCount>\n<data>\n<list>\n<name>가</name>\n<addr>서울</addr>\n</list>\n"
                        + "<list>\n<name>나</name>\n<addr>부산</addr>\n</list>\n</data>\n</body>\n</rfcOpenApi>")));

        assertEquals(List.of("rfcOpenApi.header.resultCode", "rfcOpenApi.header.resultMsg", "rfcOpenApi.body.totalCount",
                "rfcOpenApi.body.data.list[].name", "rfcOpenApi.body.data.list[].addr", "rfcOpenApi.body.data.list[].tel"),
                paths(api));
        assertEquals("XML", api.get("responseFormat").asText());
        assertFalse(api.has("responseRootless"));
    }

    @Test
    void 루트_없는_JSON_예제면_형식을_JSON으로_두고_루트_없는_응답으로_적는다() {
        // 계룡시 등: 응답 형식 파라미터가 없고 {"header": …, "body": […]}로 답한다
        JsonNode api = api(standard(fields(), example("{\n  \"body\": [\n    {\"name\": \"경로당\", \"addr\": \"계룡\"}\n  ],\n"
                + "  \"header\": {\"resultCode\": \"00\", \"resultMsg\": \"NORMAL SERVICE\"}\n}")));

        assertEquals("JSON", api.get("responseFormat").asText());
        assertTrue(api.get("responseRootless").asBoolean());
        assertEquals(List.of("header.resultCode", "header.resultMsg", "body[].name", "body[].addr"), paths(api));
    }

    @Test
    void 응답_형식_파라미터가_있으면_다른_형식의_예제로_형식을_바꾸지_않는다() {
        List<Block> blocks = document(table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키"),
                row("returnType", "응답형식", "4", "0", "xml", "xml 또는 json")), fields(),
                example("{\"body\": [{\"name\": \"가\", \"addr\": \"나\"}], \"header\": {\"resultCode\": \"00\"}}"));

        JsonNode api = api(blocks);

        assertEquals("XML", api.get("responseFormat").asText());
        assertEquals("response.body.items.item[].name", paths(api).get(2));
    }

    @Test
    void 표의_필드가_예제에서_절반도_찾아지지_않으면_표준_구조를_쓴다() {
        // 다른 오퍼레이션의 예제이거나 표와 다른 응답일 수 있다
        JsonNode api = api(standard(fields(), example(
                "<root><info><totalCount>1</totalCount></info><items><jmInfo><jmCd>1</jmCd></jmInfo></items></root>")));

        assertEquals(List.of("response.header.resultCode", "response.header.resultMsg",
                "response.body.items.item[].name", "response.body.items.item[].addr"), paths(api));
    }

    @Test
    void 대소문자만_다른_이름은_예제의_표기로_고친다() {
        // 기상청 중기예보: 표는 regid, 응답은 regId
        JsonNode api = api(standard(table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("regid", "예보구역코드", "8", "1", "11B00000", "예보구역코드"),
                        row("Latitude", "위도", "10", "0", "37.5", "위도")),
                example("<response><header><resultCode>00</resultCode><resultMsg>NORMAL_SERVICE</resultMsg></header>"
                        + "<body><items><item><regId>11B00000</regId><latitude>37.5</latitude></item></items></body>"
                        + "</response>")));

        assertEquals(List.of("response.header.resultCode", "response.body.items.item[].regId",
                "response.body.items.item[].latitude"), paths(api));
        assertTrue(notes(api).contains("응답 예제의 표기로 응답 필드 이름을 고침: regid → regId"), notes(api).toString());
    }

    @Test
    void 요청_표에_인증키가_없으면_요청_예제_주소의_인증키를_넣는다() {
        // 전주시·제주도: 요청 표는 검색 조건만 적고 인증키(authApiKey)는 예제 주소에만 있다
        String callBack = "http://openapi.jeonju.go.kr/rest/medicalnew/getMedicalList";
        List<Block> blocks = new ArrayList<>(List.of(
                heading("가. 상세기능정보"),
                table(row("Call Back URL", callBack)),
                heading("나. 요청 메시지 명세"),
                table(HEADER, row("mediName", "병원명", "50", "0", "병원", "병원명")),
                heading("다. 응답 메시지 명세"),
                fields(),
                heading("라. 요청/응답 메시지 예제"),
                table(row("요청메시지"), row(callBack + "?authApiKey=인증키&mediName=병원"))));

        JsonNode api = api(blocks);

        JsonNode key = api.get("requestParameters").get(0);
        assertEquals("authApiKey", key.get("name").asText());
        assertTrue(key.get("required").asBoolean());
        assertEquals("mediName", api.get("requestParameters").get(1).get("name").asText());
    }

    @Test
    void 요청_표에_인증키가_있으면_예제_주소로_더하지_않는다() {
        String callBack = "http://openapi.jeonju.go.kr/rest/medicalnew/getMedicalList";
        List<Block> blocks = new ArrayList<>(List.of(
                heading("가. 상세기능정보"),
                table(row("Call Back URL", callBack)),
                heading("나. 요청 메시지 명세"),
                table(HEADER, row("ServiceKey", "인증키", "100", "1", "키", "인증키")),
                heading("다. 응답 메시지 명세"),
                fields(),
                heading("라. 요청/응답 메시지 예제"),
                table(row("요청메시지"), row(callBack + "?authApiKey=인증키"))));

        JsonNode api = api(blocks);

        assertEquals(1, api.get("requestParameters").size());
        assertEquals("ServiceKey", api.get("requestParameters").get(0).get("name").asText());
    }

    @Test
    void 예제가_없는_문서는_지금까지와_같다() {
        JsonNode api = api(standard(fields()));

        assertEquals("00", api.at("/errorSpec/successResultCode").asText());
        assertEquals(List.of("response.header.resultCode", "response.header.resultMsg",
                "response.body.items.item[].name", "response.body.items.item[].addr"), paths(api));
        assertEquals("XML", api.get("responseFormat").asText());
    }

    /** 결과코드·메시지와 데이터 필드 둘 */
    private Block.Table fields() {
        return table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                row("resultMsg", "결과메시지", "50", "1", "NORMAL SERVICE", "결과메시지"),
                row("name", "이름", "50", "0", "센터", "이름"),
                row("addr", "주소", "50", "0", "서울", "주소"));
    }

    /** 응답 예제 표. 칸 하나에 예제 하나를 담는다 */
    private Block.Table example(String... responses) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("응답메시지"));
        for (String response : responses) {
            rows.add(List.of(response));
        }
        return new Block.Table(rows);
    }

    private List<Block> standard(Block.Table response, Block.Table... more) {
        return document(table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")), response, more);
    }

    /** 정보/요청/응답 구간을 갖춘 표준 문서. 뒤에 붙인 표는 예제 구간에 온다 */
    private List<Block> document(Block.Table request, Block.Table response, Block.Table... more) {
        List<Block> blocks = new ArrayList<>(List.of(
                heading("가. 상세기능정보"),
                table(row("Call Back URL", CALL_BACK)),
                heading("나. 요청 메시지 명세"),
                request,
                heading("다. 응답 메시지 명세"),
                response));
        if (more.length > 0) {
            blocks.add(heading("라. 요청/응답 메시지 예제"));
            blocks.addAll(List.of(more));
        }
        return blocks;
    }

    /** 마지막으로 만든 IR. 검수 노트를 본다 */
    private JsonNode lastIr;

    private JsonNode api(List<Block> blocks) {
        List<ParsedApi> apis = new SpecBlockAssembler().parse(blocks, "example.hwp", "HWP");
        assertEquals(1, apis.size(), "오퍼레이션 수");
        lastIr = apis.get(0).ir();
        return lastIr.get("api");
    }

    private List<String> paths(JsonNode api) {
        List<String> paths = new ArrayList<>();
        api.get("responseFields").forEach(field -> paths.add(field.get("path").asText()));
        return paths;
    }

    private List<String> notes(JsonNode api) {
        List<String> notes = new ArrayList<>();
        lastIr.at("/metadata/reviewNotes").forEach(note -> notes.add(note.asText()));
        return notes;
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
}
