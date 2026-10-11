package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.emit.ClientEmitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 두 엔진이 같이 쓰는 IR 규칙을 공개 진입점으로 확인한다. llm 엔진은 표의 행 없이 칸의 원래 글과
 * 주소 후보만으로 이 함수들을 부르므로, 여기서도 그렇게 부른다. 예시는 실제 기관 문서에서 모았다.
 */
class IrConventionsTest {

    private static final String SERVICE = "http://apis.data.go.kr/B551011/WellnessTursmService";

    @Test
    void 포맷_파라미터_이름은_generator의_ClientEmitter와_같다() throws Exception {
        // 두 목록이 어긋나면 클라이언트는 포맷 파라미터를 보내지 않는데 DTO는 그 포맷을 기대해 응답을 읽지 못한다
        Field field = ClientEmitter.class.getDeclaredField("RESPONSE_FORMAT_PARAMS");
        field.setAccessible(true);

        assertEquals(field.get(null), IrConventions.responseFormatParameters());
    }

    @Test
    void 칸의_주소에서_질의_문자열과_끝_문장부호를_지운다() {
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList",
                IrConventions.firstUrl("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList?serviceKey=키&pageNo=1"));
        assertEquals("https://portal.kosha.or.kr/openapi/v1/koshagw",
                IrConventions.firstUrl("요청 주소는 https://portal.kosha.or.kr/openapi/v1/koshagw. 입니다"), "산문 속 주소");
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList",
                IrConventions.firstUrl("http://apis.data.go.kr/B552584/\nMsrstnInfoInqireSvc/getMsrstnList"),
                "칸 폭 때문에 줄이 바뀐 주소");
        assertNull(IrConventions.firstUrl("주소 없음"));
        assertNull(IrConventions.firstUrl(null));
    }

    @Test
    void Call_Back_URL_칸은_꺾인_주소를_잇고_N_A는_없는_것으로_본다() {
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList",
                IrConventions.callBackUrl("http://apis.data.go.kr/B552584/ MsrstnInfoInqireSvc/getMsrstnList"));
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList",
                IrConventions.callBackUrl("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList (측정소 목록)"),
                "한글 설명이 붙은 칸은 잇지 않고 주소만 꺼낸다");
        assertNull(IrConventions.callBackUrl("N/A"));
        assertNull(IrConventions.callBackUrl("  "));
        assertNull(IrConventions.callBackUrl("portal.kosha.or.kr/openapi/v1/koshagw"), "스킴 없는 주소는 따로 꺼낸다");
    }

    @Test
    void 스킴_없는_주소는_칸_전체가_주소꼴일_때만_꺼낸다() {
        assertEquals("portal.kosha.or.kr/openapi/v1/koshagw",
                IrConventions.schemelessUrl(" portal.kosha.or.kr/openapi/v1/koshagw/ "));
        assertNull(IrConventions.schemelessUrl("https://portal.kosha.or.kr/openapi/v1/koshagw"));
        assertNull(IrConventions.schemelessUrl("서비스URL/getBidPbancRsltInfo"), "자리표시");
    }

    @Test
    void 서비스_URL_칸은_http로_시작할_때만_꺾인_주소를_이어_읽는다() {
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc",
                IrConventions.serviceUrl("http://apis.data.go.kr/B552584/\nMsrstnInfoInqireSvc"));
        assertNull(IrConventions.serviceUrl("운영: http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc"));
    }

    @Test
    void 영문_오퍼레이션명은_공백_없는_식별자일_때만_쓴다() {
        assertEquals("getMsrstnList", IrConventions.operationName(" getMsrstnList "));
        assertNull(IrConventions.operationName("getMsrstnList(측정소 목록)"));
        assertNull(IrConventions.operationName("측정소 목록 조회"));
        assertNull(IrConventions.operationName("get Msrstn List"));
    }

    @Test
    void 이름_칸의_원래_글을_rule_엔진과_같게_다듬는다() {
        assertEquals("title", IrConventions.cleanName("└ title"));
        assertEquals("numOfRows", IrConventions.cleanName("numOfRows\n(한 페이지 결과 수)"));
        assertEquals("TEST_EVAL_ISUE_DD", IrConventions.cleanName("TEST_EVAL_IS\nUE_DD"));
        assertEquals(FieldColumns.cleanName("ndrgItmIcsn TpCd"), IrConventions.cleanName("ndrgItmIcsn TpCd"));
    }

    @Test
    void Call_Back_URL이_있으면_그_주소를_쓴다() {
        List<String> notes = new ArrayList<>();
        String callBack = SERVICE + "/areaBasedList";

        assertEquals(callBack, endpoint(new UrlCandidates(callBack, null, SERVICE + "/tableUrl", null,
                null, SERVICE, "areaBasedList", false), notes));
        assertEquals(List.of(), notes);
    }

    @Test
    void Call_Back_URL이_서비스_URL_밖을_가리키면_요청_예제를_쓰고_노트를_남긴다() {
        // 한국관광공사 웰니스: Call Back URL의 서비스 경로를 오퍼레이션명으로 잘못 적었다
        String wrong = "http://apis.data.go.kr/B551011/wellnessTursmSyncList/wellnessTursmSyncList";
        String example = "https://apis.data.go.kr/B551011/WellnessTursmService/wellnessTursmSyncList";
        List<String> notes = new ArrayList<>();

        assertEquals(example, endpoint(new UrlCandidates(wrong, null, null, null, example, SERVICE, null, false), notes));
        assertEquals(List.of("Call Back URL이 서비스 URL 밖을 가리켜 요청 예제의 주소를 씀: " + wrong), notes);
    }

    @Test
    void Call_Back_URL_아래_경로를_부르는_요청_예제를_쓴다() {
        // 국립해양조사원 해안선통계: Call Back URL이 서비스 주소에서 멈췄다
        String example = "http://apis.data.go.kr/1192136/CoastStat_ver_21/getCoastStatInfo_ver_21";

        assertEquals(example, endpoint(new UrlCandidates("http://apis.data.go.kr/1192136/CoastStat_ver_21", null,
                null, null, example, null, "TB_COAST_STAT_INFO", false), new ArrayList<>()));
    }

    @Test
    void 표_안의_주소는_요청_예제와_서비스_URL보다_앞선다() {
        String table = "https://www.jeju.go.kr/api/exam/term";

        assertEquals(table, endpoint(new UrlCandidates(null, null, table, "http://line/getTerm",
                "http://example/getTerm", SERVICE, "getTerm", false), new ArrayList<>()));
    }

    @Test
    void 오퍼레이션명이_든_요청_예제는_서비스_URL보다_앞선다() {
        // its 신호연계: 오퍼레이션명은 trafficLightList지만 실제 주소는 …/trafficLightList4다
        String example = "http://apis.data.go.kr/B551982/rti/trafficLightList4";

        assertEquals(example, endpoint(new UrlCandidates(null, null, null, null, example,
                "http://its.go.kr/api", "trafficLightList", false), new ArrayList<>()));
    }

    @Test
    void 서비스_URL에_오퍼레이션명을_붙이되_이미_담겼으면_다시_붙이지_않는다() {
        assertEquals("http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getHoliDeInfo",
                endpoint(new UrlCandidates(null, null, null, null, null,
                        "http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/", "getHoliDeInfo", false),
                        new ArrayList<>()));
        assertEquals("https://openapi.kpx.or.kr/openapi/sumperfuel5m/getSumperfuel5m",
                endpoint(new UrlCandidates(null, null, null, null, null,
                        "https://openapi.kpx.or.kr/openapi/sumperfuel5m/getSumperfuel5m", "getSumperfuel5m", false),
                        new ArrayList<>()));
    }

    @Test
    void 요청_예제가_서비스_주소_자체를_부르면_서비스_URL에_오퍼레이션명을_붙이지_않는다() {
        // 국립해양조사원: 오퍼레이션명(영문)이 주소에 없는 이름이어도 요청 예제가 부르는 서비스 주소가 호출 주소다
        String service = "http://apis.data.go.kr/1192136/fcstSeafog/GetFcstSeafogApiService";
        String example = "https://apis.data.go.kr/1192136/fcstSeafog/GetFcstSeafogApiService";

        assertEquals(example, endpoint(new UrlCandidates(null, null, null, null, example, service,
                "getSeafogInfo", false), new ArrayList<>()));
    }

    @Test
    void 요청_URL_문단은_오퍼레이션명_없는_요청_예제보다_앞선다() {
        String line = "http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInList";

        assertEquals(line, endpoint(new UrlCandidates(null, null, null, line, "http://api.nongsaro.go.kr/sample",
                null, null, false), new ArrayList<>()));
        assertEquals("http://api.nongsaro.go.kr/sample", endpoint(new UrlCandidates(null, null, null, null,
                "http://api.nongsaro.go.kr/sample", null, null, false), new ArrayList<>()));
    }

    @Test
    void 스킴_없는_Call_Back_URL에는_https를_붙이고_노트를_남긴다() {
        List<String> notes = new ArrayList<>();

        assertEquals("https://portal.kosha.or.kr/openapi/v1/koshagw", endpoint(new UrlCandidates(null,
                "portal.kosha.or.kr/openapi/v1/koshagw", null, null, null, SERVICE, null, false), notes));
        assertEquals(List.of("Call Back URL에 스킴이 없어 https://를 붙임: portal.kosha.or.kr/openapi/v1/koshagw"), notes);
    }

    @Test
    void 다른_후보가_없으면_서비스_URL을_쓰되_공통_구간은_오퍼레이션_주소를_얻지_않는다() {
        List<String> notes = new ArrayList<>();
        assertEquals(SERVICE, endpoint(new UrlCandidates(null, null, null, null, null, SERVICE, null, false), notes));
        assertEquals(List.of("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀"), notes);

        List<String> commonNotes = new ArrayList<>();
        assertNull(endpoint(new UrlCandidates(null, null, null, null, null, SERVICE, null, true), commonNotes));
        assertEquals(List.of(), commonNotes);
    }

    @Test
    void 빈_문자열_후보는_없는_것으로_본다() {
        // LLM 중간 스키마는 값이 없으면 빈 문자열을 쓴다
        UrlCandidates candidates = new UrlCandidates("", " ", "", "", "", "", "", false);

        assertNull(candidates.callBackUrl());
        assertNull(candidates.operationName());
        assertNull(endpoint(candidates, new ArrayList<>()));
    }

    @Test
    void 같은_주소를_가진_오퍼레이션은_자기_요청_예제의_주소로_바로잡는다() {
        // 한국관광공사 TourAPI: 세 번째 기능이 첫 기능의 Call Back URL을 베꼈다
        String first = "https://apis.data.go.kr/B551011/AreaTarDivService/areaTouDivList";
        String own = "https://apis.data.go.kr/B551011/AreaTarDivService/areaIntlDivList";
        List<List<String>> notes = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());

        List<String> urls = IrConventions.correctSharedUrls(List.of(first, first, first, first),
                java.util.Arrays.asList(first, own, "https://other.host/areaIntlDivList", null), notes);

        assertEquals(List.of(first, own, first, first), urls, "다른 호스트를 부르는 예제는 따르지 않는다");
        assertEquals(List.of(), notes.get(0));
        assertEquals(List.of("Call Back URL이 다른 오퍼레이션과 같아 요청 예제의 주소를 씀: " + first), notes.get(1));
        assertEquals(List.of(), notes.get(2));
        assertEquals(List.of(), notes.get(3));
    }

    @Test
    void 겹침은_바로잡기_전_주소로_판정한다() {
        // 전통식품정보: 두 기능이 같은 주소를 적었으면 앞 것을 고친 뒤에도 뒤 것까지 각자 예제를 따른다
        String culture = "http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodHistoryCultureList";
        String history = "http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodHistoryList";
        String cultureOnly = "http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodCultureList";

        List<String> urls = IrConventions.correctSharedUrls(List.of(culture, culture), List.of(history, cultureOnly),
                List.of(new ArrayList<>(), new ArrayList<>()));

        assertEquals(List.of(history, cultureOnly), urls);
    }

    @Test
    void 주소_비교는_스킴과_끝_슬래시와_대소문자를_무시한다() {
        assertTrue(IrConventions.sameUrl("http://Apis.data.go.kr/B552584/Svc/", "https://apis.data.go.kr/b552584/svc"));
        assertFalse(IrConventions.sameUrl("http://apis.data.go.kr/B552584/Svc", "http://apis.data.go.kr/B552584/Svc2"));
    }

    @Test
    void 주소를_baseUrl_endpoint_apiId로_나눈다() {
        IrConventions.UrlParts parts =
                IrConventions.splitUrl("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList/");

        assertTrue(parts.ok());
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc", parts.baseUrl());
        assertEquals("/getMsrstnList", parts.endpoint(), "끝 슬래시는 지운다");
        assertEquals("MsrstnList", parts.apiId());
        assertNull(parts.error());
    }

    @Test
    void 끝의_경로_변수는_endpoint에_남기고_그_앞_칸으로_apiId를_만든다() {
        IrConventions.UrlParts parts =
                IrConventions.splitUrl("https://open.jejudatahub.net/api/proxy/7ttta5/{your_appkey}");

        assertEquals("https://open.jejudatahub.net/api/proxy", parts.baseUrl());
        assertEquals("/7ttta5/{your_appkey}", parts.endpoint());
        assertEquals("Api7ttta5", parts.apiId(), "숫자로 시작하는 경로는 클래스명이 되도록 접두사를 붙인다");
    }

    @Test
    void apiId는_클래스명으로_쓸_수_있게_만든다() {
        assertEquals("Get24DivisionsInfo", apiId("http://apis.data.go.kr/B090041/svc/get24DivisionsInfo"),
                "get을 떼면 숫자로 시작하는 이름은 get을 남긴다");
        assertEquals("ListSearch_do", apiId("https://api.example.kr/openapi/listSearch.do"));
        assertEquals("CallEmg02Api", apiId("http://apis.data.go.kr/1352000/ODMS_EMG_02/callEmg02Api"));
    }

    @Test
    void 나눌_수_없는_주소는_예외_대신_이유를_돌려준다() {
        for (String url : List.of("getFoo", "http://{host}")) {
            IrConventions.UrlParts parts = IrConventions.splitUrl(url);
            assertFalse(parts.ok(), url);
            assertTrue(parts.error().contains("오퍼레이션 경로를 찾을 수 없습니다"), parts.error());
            assertTrue(parts.error().contains(url), parts.error());
            assertNull(parts.baseUrl());
            assertNull(parts.endpoint());
            assertNull(parts.apiId());
        }
        assertFalse(IrConventions.splitUrl(null).ok());
    }

    @Test
    void 경로_변수가_요청_파라미터에_없으면_필수_path_파라미터로_앞에_채운다() {
        ArrayNode params = parameters("pageNo", "areaCode");

        IrConventions.addPathParameters(params, "/getList/{your_appkey}/{areaCode}");

        assertEquals(List.of("your_appkey", "pageNo", "areaCode"), names(params));
        assertEquals("path", params.get(0).get("in").asText());
        assertTrue(params.get(0).get("required").asBoolean());
        assertEquals("주소의 {your_appkey} 자리에 넣는 값", params.get(0).get("description").asText());
        assertEquals("path", params.get(2).get("in").asText(), "같은 이름의 query 파라미터는 path로 바꾼다");
        assertTrue(params.get(2).get("required").asBoolean());
    }

    @Test
    void 게이트웨이_주소에_serviceKey가_없을_때만_보충한다() {
        String gateway = "http://apis.data.go.kr/1160100/service/GetCorpBasicInfoService/getCorpActionDtList";

        assertTrue(IrConventions.needsGatewayServiceKey(gateway, List.of("issucoCustno")));
        assertTrue(IrConventions.needsGatewayServiceKey("HTTPS://APIS.DATA.GO.KR/1/svc/getX", List.of()));
        assertFalse(IrConventions.needsGatewayServiceKey(gateway, List.of("ServiceKey", "issucoCustno")),
                "KASI 문서처럼 대소문자가 달라도 같은 인증키다");
        assertFalse(IrConventions.needsGatewayServiceKey(
                "http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInList", List.of("apiKey")),
                "기관 서버는 인증 방식이 제각각이라 채우지 않는다");
    }

    @Test
    void 보충한_serviceKey는_경로_변수_파라미터_다음에_온다() {
        ArrayNode params = parameters("pageNo");

        IrConventions.insertGatewayServiceKey(params);
        IrConventions.addPathParameters(params, "/getList/{areaCode}");

        assertEquals(List.of("areaCode", "serviceKey", "pageNo"), names(params));
        JsonNode serviceKey = params.get(1);
        assertEquals("query", serviceKey.get("in").asText());
        assertEquals("string", serviceKey.get("type").asText());
        assertTrue(serviceKey.get("required").asBoolean());
        assertEquals("공공데이터포털에서 발급받은 인증키", serviceKey.get("description").asText());
    }

    @Test
    void 결과코드와_결과메시지는_표기가_달라도_알아본다() {
        for (String name : List.of("resultCode", "RESULT_CODE", "result_msg", "resultMessage")) {
            assertTrue(IrConventions.isResultCodeOrMessageField(name), name);
        }
        assertFalse(IrConventions.isResultCodeOrMessageField("resultCnt"));
        assertTrue(IrConventions.isResultCodeField("RESULT_CODE"));
        assertFalse(IrConventions.isResultCodeField("resultMsg"));
    }

    @Test
    void 앞자리_0이_붙은_숫자만_골라낸다() {
        assertTrue(IrConventions.hasLeadingZero("0311"));
        assertTrue(IrConventions.hasLeadingZero("00"));
        for (String value : List.of("0", "10", "0.5", "", "O1")) {
            assertFalse(IrConventions.hasLeadingZero(value), value);
        }
    }

    @Test
    void 국문_기능명을_쓸_수_없으면_apiId를_apiName으로_쓴다() {
        assertEquals("측정소 목록 조회", IrConventions.apiName(" 측정소 목록\n조회 ", "MsrstnList"));
        for (String title : java.util.Arrays.asList("getMsrstnList", "N/A", "n/a", " ", null)) {
            assertEquals("MsrstnList", IrConventions.apiName(title, "MsrstnList"), String.valueOf(title));
        }
        assertTrue(IrConventions.isDisplayText("TM 기준좌표 조회"));
        assertFalse(IrConventions.isDisplayText("getTMStdrCrdnt"));
    }

    @Test
    void 항목설명이_비면_국문_항목명을_설명으로_쓴다() {
        assertEquals("측정소명", IrConventions.description(" ", "측정소명"));
        assertEquals("측정소 이름", IrConventions.description("측정소\n 이름", "측정소명"));
    }

    @Test
    void 여러_줄의_글을_한_줄로_잇는다() {
        assertEquals("a b c", IrText.oneLine(" a \n b\n\nc "));
    }

    @Test
    void 문서가_알려_주지_않는_errorSpec과_generatorHints와_metadata의_기본값을_만든다() {
        ObjectNode errorSpec = IrConventions.errorSpec();
        assertEquals("00", errorSpec.get("successResultCode").asText());
        assertEquals(List.of("response.header.resultCode", "resultCode", "RESULT_CODE", "cmmMsgHeader.returnReasonCode"),
                texts(errorSpec.get("resultCodeFieldCandidates")));

        ObjectNode hints = IrConventions.generatorHints("MsrstnList");
        assertEquals("kr.go.h2spec.client.msrstnlist", hints.get("targetPackage").asText());
        assertEquals("RestTemplate", hints.get("targetClientType").asText());

        ObjectNode clean = IrConventions.metadata("a.docx", "DOCX", List.of());
        assertFalse(clean.get("manualReviewRequired").asBoolean());
        assertEquals(List.of("sourceFile", "sourceFormat", "parsedAt", "parserVersion", "engine",
                "manualReviewRequired", "reviewNotes"), fieldNames(clean));
        assertEquals("rule", clean.get("engine").asText());
        ObjectNode noted = IrConventions.metadata("a.hwp", "HWP", List.of("노트"));
        assertTrue(noted.get("manualReviewRequired").asBoolean(), "노트가 하나라도 있으면 검수가 필요하다");
        assertEquals(List.of("노트"), texts(noted.get("reviewNotes")));
        assertEquals("HWP", noted.get("sourceFormat").asText());
    }

    private String endpoint(UrlCandidates candidates, List<String> notes) {
        return IrConventions.endpointUrl(candidates, notes);
    }

    private String apiId(String url) {
        return IrConventions.splitUrl(url).apiId();
    }

    /** 문서의 요청 표에서 온 query 파라미터 배열 */
    private ArrayNode parameters(String... names) {
        ArrayNode params = JsonNodeFactory.instance.arrayNode();
        for (String name : names) {
            ObjectNode param = params.addObject();
            param.put("name", name);
            param.put("in", "query");
            param.put("type", "string");
            param.put("required", false);
            param.put("description", name);
        }
        return params;
    }

    private List<String> names(ArrayNode params) {
        List<String> names = new ArrayList<>();
        params.forEach(param -> names.add(param.get("name").asText()));
        return names;
    }

    private List<String> texts(JsonNode array) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.asText()));
        return texts;
    }

    private List<String> fieldNames(ObjectNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
