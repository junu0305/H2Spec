package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 공공데이터포털에 올라온 기관 문서 수백 건을 돌려 보며 찾은, 표준 기술문서와 다른 구간·표 배치를 다룬다.
 * 각 테스트의 블록 목록은 실제 문서의 구조를 최소로 줄인 것이고, 주석에 원래 문서를 적었다.
 */
class SpecBlockLayoutTest {

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
    private static final String CALL_BACK = "http://apis.data.go.kr/1352000/ODMS_EMG_02/callEmg02Api";

    @Test
    void 띄어쓰기와_맞춤법이_다른_구간_제목을_알아본다() {
        // 보건복지부 "나. 상세기능 정보", 예금보험공사 "요청메시지 명세", 한국에너지공단 "- 응답 메세지 명세"
        List<ParsedApi> apis = parse(List.of(
                heading("나. 상세기능 정보"),
                table(row("상세기능 번호", "1", "상세기능 유형", "조회"), row("Call Back URL", CALL_BACK)),
                heading("다. 요청메시지 명세"),
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")),
                heading("- 응답 메세지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));

        assertEquals(1, apis.size());
        assertEquals("/callEmg02Api", api(apis, 0).get("endpoint").asText());
    }

    @Test
    void 목차_항목은_구간을_열지_않는다() {
        // 목차의 "다. 요청 메시지 명세→5"가 요청 구간을 열면 뒤따르는 서비스 개요 표를 필드 표로 삼켜
        // 서비스 URL을 잃는다. Call Back URL이 N/A인 문서는 그 주소가 있어야 엔드포인트를 만든다
        List<ParsedApi> apis = parse(List.of(
                heading("다. 요청 메시지 명세\t5"),
                heading("라. 응답 메시지 명세\t5"),
                heading("가. API 서비스 개요"),
                table(row("API 서비스배포정보", "서비스 URL", "http://apis.data.go.kr/1352000/ODMS_EMG_02")),
                heading("나. 상세기능 정보"),
                table(row("상세기능명(국문)", "운영기관 조회"), row("오퍼레이션명(영문)", "callEmg02Api"),
                        row("Call Back URL", "N/A")),
                heading("다. 요청 메시지 명세"),
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")),
                heading("라. 응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));

        assertEquals(CALL_BACK, url(apis, 0));
        assertEquals("운영기관 조회", api(apis, 0).get("apiName").asText());
    }

    @Test
    void 오퍼레이션_명세_제목과_오퍼레이션번호_정보표를_알아본다() {
        // 예금보험공사: "OOO 오퍼레이션 명세" 아래 정보 표가 "오퍼레이션번호"로 시작한다
        List<ParsedApi> apis = parse(List.of(
                heading("계좌별 수신 신규현황 조회 오퍼레이션 명세"),
                table(row("오퍼레이션번호", "1", "오퍼레이션명(국문)", "계좌별 수신 신규현황 조회"),
                        row("오퍼레이션설명", "수신 신규 현황"),
                        row("Call Back URL", "http://apis.data.go.kr/B190017/service/GetSvc/getAcnut")),
                heading("계좌별 수신 신규현황 조회 오퍼레이션 요청메시지 명세"),
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                heading("계좌별 수신 신규현황 조회 오퍼레이션 응답메시지 명세"),
                table(HEADER, row("svbkNm", "저축은행명", "100", "0", "경기", "저축은행명"))));

        JsonNode api = api(apis, 0);
        assertEquals("계좌별 수신 신규현황 조회", api.get("apiName").asText());
        assertEquals("수신 신규 현황", api.get("description").asText());
        assertTrue(hasField(api, "response.body.items.item[].svbkNm"));
    }

    @Test
    void 항목크기_열이_없는_다섯칸_표도_읽는다() {
        // 중앙선거관리위원회 등: 항목크기 열을 빼고 다섯 칸만 둔다. 여섯 칸 미만 행을 각주로 버리면 표가 통째로 빠진다
        List<String> header = List.of("항목명(영문)", "항목명(국문)", "항목구분", "샘플데이터", "항목 설명");
        List<ParsedApi> apis = parse(standard(
                table(header, row("sgId", "선거ID", "1", "20220601", "선거ID")),
                table(header, row("huboid", "후보자ID", "0", "100138362", "후보자ID"))));

        JsonNode param = api(apis, 0).get("requestParameters").get(1);
        assertEquals("sgId", param.get("name").asText());
        assertTrue(param.get("required").asBoolean());
        assertEquals("선거ID", param.get("description").asText());
    }

    @Test
    void 요청_URL_문단과_요청변수_응답변수_표를_오퍼레이션마다_읽는다() {
        // 농촌진흥청 농사로: 정보 표 없이 "● 요청 URL : …" 문단이 오퍼레이션을 열고 두 칸짜리 응답 표를 둔다
        List<String> request = List.of("요청변수", "설명", "필수 여부");
        List<String> response = List.of("응답변수", "설명");
        List<ParsedApi> apis = parse(List.of(
                heading("4.2.1 exportDataZoomInList(수출 자료 Zoom-In 목록)"),
                heading("● 요청 URL : http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInList"),
                heading("● 요청 변수(Request Parameters)"),
                table(request, row("apiKey", "발급받은 Open API 인증키", "○"), row("sText", "검색 단어", "")),
                heading("● 응답 결과(Response Element)"),
                table(response, row("cntntsNo", "콘텐츠 번호(키)")),
                heading("4.2.2 exportDataZoomInView(수출 자료 Zoom-In 상세정보)"),
                heading("● 요청 URL : http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInView"),
                heading("● 요청 변수(Request Parameters)"),
                table(request, row("apiKey", "발급받은 Open API 인증키", "○")),
                heading("● 응답 결과(Response Element)"),
                table(response, row("cntntsSj", "제목"))));

        assertEquals(2, apis.size());
        assertEquals("http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInView", url(apis, 1));
        JsonNode apiKey = api(apis, 0).get("requestParameters").get(0);
        assertTrue(apiKey.get("required").asBoolean(), "필수 여부 칸의 ○");
        assertFalse(api(apis, 0).get("requestParameters").get(1).get("required").asBoolean());
        assertTrue(hasField(api(apis, 1), "response.body.items.item[].cntntsSj"));
    }

    @Test
    void 공통_파라미터와_JSON_응답_예제만_있는_문서를_읽는다() {
        // 제주데이터허브: "1. 데이터 공통 사항"의 페이징 파라미터가 모든 API에 붙고, API별 응답은 JSON 예제로만 준다.
        // 주소 끝의 {your_appkey}는 경로 변수다
        List<String> header = List.of("Name", "Description", "Type", "Note");
        List<ParsedApi> apis = parse(List.of(
                heading("1. 데이터 공통 사항"),
                heading("Request Parameters"),
                table(header, row("number", "페이지 번호", "integer", "default=1")),
                heading("Response"),
                table(header, row("totCnt", "요청 컨텐츠 총 개수", "long", ""),
                        row("data", "요청 컨텐츠", "json array", "")),
                heading("2. 읍면동별 와이파이 사용량 API"),
                heading("요청 URL"),
                heading("https://open.jejudatahub.net/api/proxy/7ttta5/{your_appkey}?{params(key=value)}"),
                heading("Request Parameters"),
                table(header, row("searchDate", "분석일(필수)", "string", "YYYYMMDD")),
                heading("Response Body"),
                table(row("{  \"totCnt\": 13,  \"hasMore\": true,  \"data\": [ {  \"baseDate\": \"20190704\","
                        + "  \"userCount\": 21 }, { \"baseDate\": “20190705” … } ] }"))));

        assertEquals(1, apis.size(), "공통 사항 구간은 오퍼레이션이 아니다");
        JsonNode api = api(apis, 0);
        assertEquals("https://open.jejudatahub.net/api/proxy", api.get("baseUrl").asText());
        assertEquals("/7ttta5/{your_appkey}", api.get("endpoint").asText());
        assertEquals("Api7ttta5", api.get("apiId").asText(), "숫자로 시작하는 경로는 클래스명이 되도록 접두사를 붙인다");
        assertEquals(List.of("your_appkey", "number", "searchDate"), names(api.get("requestParameters"), "name"));
        assertEquals("path", api.get("requestParameters").get(0).get("in").asText());
        assertTrue(api.get("requestParameters").get(2).get("required").asBoolean(), "설명의 (필수)");
        assertEquals(List.of("totCnt", "hasMore", "baseDate", "userCount"), leafNames(api));
        assertTrue(api.get("responseFields").get(0).get("description").asText().contains("총 개수"),
                "공통 응답 표의 설명을 예제 키보다 앞세운다");
        assertTrue(apis.get(0).ir().get("metadata").get("manualReviewRequired").asBoolean());
    }

    @Test
    void 한_표에_담긴_입력값_출력값_명세를_나눈다() {
        // 한국예탁결제원 세부항목: 표 안의 "입력값 명세"/"출력값 명세" 행이 요청과 응답을 가른다
        List<String> header = List.of("순번", "변수명", "", "변수설명", "항목크기", "필수여부", "비고");
        List<ParsedApi> apis = parse(List.of(
                heading("(5) getCorpActionDtList"),
                heading("● 요청 URL : http://apis.data.go.kr/1160100/service/GetCorpBasicInfoService/getCorpActionDtList"),
                table(row("상세기능명", "", "주식권리일정 조회", "", "", "", ""),
                        row("입력값 명세", "", "", "", "", "", ""),
                        header,
                        row("1", "issucoCustno", "", "발행회사번호", "20", "1", "기업정보서비스 참조"),
                        row("출력값 명세", "", "", "", "", "", ""),
                        header,
                        row("1", "agOrgTpcd", "", "대행기관구분코드", "2", "1", "2번 대행기관명 참조"))));

        JsonNode api = api(apis, 0);
        assertEquals(List.of("serviceKey", "issucoCustno"), names(api.get("requestParameters"), "name"),
                "게이트웨이 주소인데 표에 serviceKey가 없으면 채운다");
        assertEquals(List.of("agOrgTpcd"), leafNames(api));
        assertEquals("주식권리일정 조회", api.get("apiName").asText());
    }

    @Test
    void 쪽_나눔으로_갈라진_응답_표를_이어_읽는다() {
        // 머리행 없이 같은 폭으로 이어지는 표는 앞 표의 연속이다
        List<ParsedApi> apis = parse(standard(
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                table(row("stationName", "측정소명", "30", "1", "종로구", "측정소 이름"))));

        assertEquals(List.of("resultCode", "stationName"), leafNames(api(apis, 0)));
    }

    @Test
    void 구분_열의_In_Out으로_요청과_응답을_가른다() {
        // 국토교통부 토지이음 연계 가이드: "구분 | 코드 | 코드명" 한 표에 요청·응답을 담고 하위 항목을 칸으로 들여쓴다
        List<ParsedApi> apis = parse(List.of(
                heading("서비스 개요"),
                table(row("서비스명", "쉬운규제안내서"),
                        row("서비스Url", "http://apis.data.go.kr/1611000/ebGuideBookListService/DTebGuideBookList")),
                heading("서비스 항목"),
                table(row("구분", "코드", "", "", "코드명", "샘플데이터"),
                        row("In", "serviceKey", "", "", "공공데이터포털에서 받은 인증키", ""),
                        row("Out", "response", "", "", "결과", ""),
                        row("", "", "resultCode", "", "결과코드", ""),
                        row("", "", "item", "", "-", ""),
                        row("", "", "", "CATE_CD", "분류코드", ""))));

        JsonNode api = api(apis, 0);
        assertEquals("/DTebGuideBookList", api.get("endpoint").asText());
        assertEquals(List.of("serviceKey"), names(api.get("requestParameters"), "name"));
        assertEquals(List.of("resultCode", "CATE_CD"), leafNames(api), "response·item은 감싸기만 하는 행이다");
    }

    @Test
    void 코드표는_구분_열이_있어도_필드_표로_보지_않는다() {
        // "구분 | 코드 | 코드명" 모양의 코드표가 응답 필드로 섞이면 안 된다
        List<ParsedApi> apis = parse(standard(
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));
        List<Block> withCodes = new ArrayList<>(standard(
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));
        withCodes.add(heading("참고: 지역 코드"));
        withCodes.add(table(row("구분", "코드", "코드명"), row("지역", "Adenman", "아덴만")));

        assertEquals(leafNames(api(apis, 0)), leafNames(api(parse(withCodes), 0)));
    }

    @Test
    void 서비스_주소가_호출_주소인_문서는_요청_예제를_따른다() {
        // 국립해양조사원: Call Back URL이 N/A이고 오퍼레이션명이 있지만, 요청 예제는 서비스 주소 자체를 부른다
        String service = "http://apis.data.go.kr/1192136/avgSeaCurrent/GetAvgSeaCurrentApiService";
        List<ParsedApi> apis = parse(List.of(
                heading("서비스 개요"),
                table(row("서비스URL", "개발환경", service), row("", "운영환경", service)),
                heading("평균 해류도 오퍼레이션 명세"),
                table(row("오퍼레이션유형", "조회", "오퍼레이션명(영문)", "getAvgSeaCurrentApi"), row("Call Back URL", "N/A")),
                heading("□ 평균 해류도 오퍼레이션 요청 메시지 명세"),
                table(HEADER, row("serviceKey", "API 인증키", "varchar(200)", "1", "키", "인증키")),
                heading("□ 평균 해류도 오퍼레이션 응답 메시지 명세"),
                table(HEADER, row("resultCode", "응답 메시지 코드", "varchar(2)", "1", "0", "코드"),
                        row("items", "목록", "number", "0..n", "-", "정보 목록")),
                heading("□ 요청/응답 메시지 예제"),
                table(row("REST(URL)"), row("https://apis.data.go.kr/1192136/avgSeaCurrent/GetAvgSeaCurrentApiService"
                        + "?serviceKey=KEY&type=json"))));

        assertEquals("https://apis.data.go.kr/1192136/avgSeaCurrent/GetAvgSeaCurrentApiService", url(apis, 0));
        assertEquals(List.of("resultCode"), leafNames(api(apis, 0)), "항목크기에 자료형을 적은 items 행은 컨테이너다");
    }

    @Test
    void Call_Back_URL이_자리표시면_요청_예제의_주소를_쓴다() {
        // 한국환경공단 순환자원정보센터: Call Back URL 칸에 "서비스URL/getBidPbancRsltInfo"만 적었다
        List<ParsedApi> apis = parse(List.of(
                table(row("서비스URL", "운영환경", "http://kecoapi.or.kr/kecoapi/bidPbancService")),
                heading("상세기능정보"),
                table(row("오퍼레이션명(영문)", "bidPbancRsltInfo"), row("Call Back URL", "서비스URL/getBidPbancRsltInfo")),
                heading("요청 메시지 명세"),
                table(HEADER, row("pageNo", "페이지 번호", "4", "1", "1", "페이지 번호")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                heading("요청/응답 메시지 예제"),
                table(row("REST(URI)"), row("http://apis.data.go.kr/B552584/kecoapi/bidPbancService/"
                        + "getBidPbancRsltInfo?pageNo=1&numOfRows=10"))));

        assertEquals("http://apis.data.go.kr/B552584/kecoapi/bidPbancService/getBidPbancRsltInfo", url(apis, 0));
    }

    @Test
    void Call_Back_URL이_서비스_주소에서_멈추면_요청_예제의_하위_경로를_쓴다() {
        // 국립해양조사원 해안선통계: Call Back URL이 서비스 주소뿐이고 오퍼레이션명(영문)에는 DB 테이블명을 적었다.
        // 게이트웨이는 서비스 주소만으로는 호출되지 않는다(NO_OPENAPI_SERVICE_ERROR)
        List<ParsedApi> apis = parse(List.of(
                heading("상세기능정보"),
                table(row("오퍼레이션명(영문)", "TB_COAST_STAT_INFO"),
                        row("Call Back URL", "http://apis.data.go.kr/1192136/CoastStat_ver_21")),
                heading("요청 메시지 명세"),
                table(HEADER, row("sigunguCd", "시군구코드", "5", "1", "31710", "시군구코드")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")),
                heading("요청/응답 메시지 예제"),
                table(row("REST(URI)"), row("http://apis.data.go.kr/1192136/CoastStat_ver_21/getCoastStatInfo_ver_21"
                        + "?ServiceKey=서비스키&sigunguCd=31710"))));

        assertEquals("http://apis.data.go.kr/1192136/CoastStat_ver_21/getCoastStatInfo_ver_21", url(apis, 0));
    }

    @Test
    void Call_Back_URL이_서비스_주소_밖을_가리키면_요청_예제를_따른다() {
        // 한국관광공사 웰니스: 다섯째 기능의 Call Back URL에서 서비스 경로를 오퍼레이션명으로 잘못 적었다.
        // 문서의 주소는 게이트웨이에 없고(NO_OPENAPI_SERVICE_ERROR) 요청 예제의 주소만 호출된다
        List<ParsedApi> apis = parse(List.of(
                table(row("서비스 URL", "개발환경", "http://apis.data.go.kr/B551011/WellnessTursmService")),
                heading("상세기능정보"),
                table(row("Call Back URL", "http://apis.data.go.kr/B551011/wellnessTursmSyncList/wellnessTursmSyncList")),
                heading("요청 메시지 명세"),
                table(HEADER, row("showflag", "표출여부", "1", "0", "1", "표출여부")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "0000", "결과코드")),
                heading("요청/응답 메시지 예제"),
                table(row("요청메시지"), row("※ 웰니스 관광정보 목록 조회https://apis.data.go.kr/B551011/WellnessTursmService/"
                        + "wellnessTursmSyncList?serviceKey=인증키&showflag=1"))));

        assertEquals("https://apis.data.go.kr/B551011/WellnessTursmService/wellnessTursmSyncList", url(apis, 0));
    }

    @Test
    void 스킴_없이_적은_Call_Back_URL에는_https를_붙인다() {
        // 한국산업안전보건공단: "portal.kosha.or.kr/openapi/v1/koshagw"
        List<ParsedApi> apis = parse(List.of(
                heading("상세기능정보"),
                table(row("Call Back URL", "portal.kosha.or.kr/openapi/v1/koshagw")),
                heading("요청 메시지 명세"),
                table(HEADER, row("certNo", "인증번호", "20", "문자", "24-AV3FH-0027", "인증번호")),
                heading("응답 메시지 명세"),
                table(HEADER, row("docAuthYn", "문서 진위여부", "1", "문자", "Y", "문서 진위여부"))));

        assertEquals("https://portal.kosha.or.kr/openapi/v1/koshagw", url(apis, 0));
        assertTrue(apis.get(0).ir().get("metadata").get("reviewNotes").toString().contains("스킴"));
    }

    @Test
    void 주소를_못_찾은_오퍼레이션만_건너뛰고_나머지는_변환한다() {
        // 한 오퍼레이션의 결함 때문에 문서 전체를 버리지 않는다
        SpecBlockAssembler assembler = new SpecBlockAssembler();
        List<Block> blocks = new ArrayList<>(standard(
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));
        blocks.add(heading("상세기능정보"));
        blocks.add(table(row("상세기능명(국문)", "주소 없는 기능"), row("Call Back URL", "N/A")));
        blocks.add(heading("요청 메시지 명세"));
        blocks.add(table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")));
        blocks.add(heading("응답 메시지 명세"));
        blocks.add(table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")));

        List<ParsedApi> apis = assembler.parse(blocks, "t.hwp", "HWP");

        assertEquals(1, apis.size());
        assertEquals(1, assembler.skipped().size());
        assertTrue(assembler.skipped().get(0).contains("주소"), assembler.skipped().toString());
    }

    @Test
    void 응답_제목을_단_요청_표는_내용으로_바로잡는다() {
        // 한국에너지공단: 요청 표 위에 "응답 메세지 명세"를 잘못 달았다. serviceKey가 있고 결과 코드가 없으면 요청이다
        List<ParsedApi> apis = parse(List.of(
                table(row("상세기능 번호", "26"), row("Call Back URL", "http://apis.data.go.kr/B553530/eep/EEP_27_LIST")),
                heading("- 응답 메세지 명세"),
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키"),
                        row("q1", "신청번호", "30", "0", "156170559", "신청번호")),
                heading("- 응답 메세지 명세"),
                table(HEADER, row("resultCode", "결과 코드", "2", "1", "00", "결과 코드"))));

        JsonNode api = api(apis, 0);
        assertEquals(List.of("serviceKey", "q1"), names(api.get("requestParameters"), "name"));
        assertEquals(List.of("resultCode"), leafNames(api));
    }

    @Test
    void 들여쓴_하위_필드를_감싸는_행은_필드가_아니다() {
        // 대한무역투자진흥공사: itemList > korCompList > korComp > korCompNm 처럼 칸 들여쓰기로 계층을 나타낸다
        List<String> header = List.of("항목명(영문)", "", "", "", "항목명(국문)", "항목크기", "항목구분", "항목설명");
        List<ParsedApi> apis = parse(standard(
                table(header, row("natnNm", "", "", "", "국가명", "50", "1", "국가명")),
                table(header,
                        row("resultCode", "", "", "", "결과코드", "2", "1", "결과코드"),
                        row("body", "", "", "", "", "", "1", ""),
                        row("", "itemList", "", "", "", "", "1", "항목 리스트"),
                        row("", "", "korCompList", "", "한국기업 리스트", "", "0", ""),
                        row("", "", "", "korCompNm", "한국기업명", "100", "0", "한국기업명"),
                        row("", "", "", "acplcAdvncYear", "진출년도", "4", "0", "진출년도"),
                        row("", "", "critYear", "", "기준년도", "4", "0", "기준년도"),
                        // 들여쓰기가 어긋났을 뿐 크기가 있는 실제 필드는 남긴다
                        row("natnCd", "", "", "", "국가코드", "3", "0", "국가코드"),
                        row("", "natnEngNm", "", "", "국가영문명", "100", "0", "국가영문명"))));

        assertEquals(List.of("resultCode", "korCompNm", "acplcAdvncYear", "critYear", "natnCd", "natnEngNm"),
                leafNames(api(apis, 0)));
    }

    @Test
    void 부모와_첫_자식을_한_행에_적은_트리에서_자식을_필드로_읽는다() {
        // 행정안전부 AI 공문서: "meta | doc_id"처럼 부모 이름과 첫 자식 이름을 한 행에 적고, 컨테이너 행의
        // 샘플 칸에는 값 대신 하위 구조 도식을 적었다. 묶음 라벨 행("공통")은 필드가 아니다(전통식품정보)
        List<String> header = List.of("항목명(영문)", "", "", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
        List<ParsedApi> apis = parse(standard(
                table(header, row("title", "", "", "문서제목", "256", "1", "정3.0", "제목")),
                table(header,
                        row("공통", "", "", "", "", "", "", ""),
                        row("resultCode", "", "", "결과코드", "2", "1", "00", "결과코드"),
                        row("resultList", "", "", "결과값 목록", "4", "1", "“resultList”:[{ “meta”: {...}", "목록"),
                        row("meta", "doc_id", "", "문서번호", "200", "1", "01_01_00000002", "일련번호"),
                        row("", "doc_type", "", "문서유형", "50", "1", "보도자료", "문서유형"),
                        row("data", "task", "task_class", "업무분류", "50", "0", "", "업무 분류"),
                        row("", "", "task_type", "업무유형", "50", "0", "", "업무 유형"),
                        // 이름을 두 칸에 걸쳐 쓰다 둘째 칸에 국문을 적은 행은 첫 칸이 이름이다
                        row("pageNo", "페이지", "", "페이지 번호", "4", "1", "1", "페이지 번호"))));

        assertEquals(List.of("resultCode", "doc_id", "doc_type", "task_class", "task_type", "pageNo"),
                leafNames(api(apis, 0)));
    }

    @Test
    void 대문자_결과코드도_표준_응답_헤더로_본다() {
        // 한국산업인력공단: RESULT_CODE / RESULT_MSG / TOTAL_COUNT
        List<ParsedApi> apis = parse(standard(
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")),
                table(HEADER, row("RESULT_CODE", "결과코드", "3", "1", "000", "결과코드"),
                        row("TOTAL_COUNT", "전체 건수", "10", "1", "5", "전체 건수"))));

        JsonNode fields = api(apis, 0).get("responseFields");
        assertEquals("response.header.RESULT_CODE", fields.get(0).get("path").asText());
        assertTrue(fields.get(0).get("isResultIndicator").asBoolean());
        assertEquals("response.body.TOTAL_COUNT", fields.get(1).get("path").asText());
    }

    @Test
    void 요청_표_머리의_END_POINT_URL과_트리_표지를_읽는다() {
        // 제주특별자치도청: 정보 표의 Call Back URL은 N/A이고 요청 표 첫 행에 END POINT URL을 적는다
        List<ParsedApi> apis = parse(List.of(
                table(row("오퍼레이션 정보", "오퍼레이션 번호", "1"), row("", "Call Back URL", "N/A")),
                heading("1) 요청메시지 명세"),
                table(row("메시지명(영문)/END POINT URL", "https://www.jeju.go.kr/api/exam/term", "", "", ""),
                        row("항목명", "타입", "필수", "기본값", "항목설명"),
                        row("page", "int", "", "1", "조회할 페이지")),
                heading("2) 응답메시지 명세"),
                table(row("항목명", "타입", "필수", "값 샘플", "항목설명"),
                        row("error", "boolean", "O", "false", "오류 여부"),
                        row("items", "array", "O", "", "데이터 목록"),
                        row("└ title", "string", "O", "", "제목"))));

        JsonNode api = api(apis, 0);
        assertEquals("https://www.jeju.go.kr/api/exam/term", api.get("baseUrl").asText() + api.get("endpoint").asText());
        assertEquals("integer", api.get("requestParameters").get(0).get("type").asText(), "타입 열의 int");
        assertEquals(List.of("error", "title"), leafNames(api));
        assertEquals("boolean", api.get("responseFields").get(0).get("type").asText());
    }

    @Test
    void 앞_오퍼레이션의_Call_Back_URL을_베껴_둔_오퍼레이션은_요청_예제를_따른다() {
        // 한국관광공사 TourAPI: 세 번째 기능의 Call Back URL이 첫 기능 주소 그대로다. 두 기능이 같은 주소를 가질 수 없다
        String first = "https://apis.data.go.kr/B551011/AreaTarDivService/areaTouDivList";
        List<Block> blocks = new ArrayList<>();
        for (String example : List.of(first, "https://apis.data.go.kr/B551011/AreaTarDivService/areaIntlDivList")) {
            blocks.add(heading("상세기능정보"));
            blocks.add(table(row("Call Back URL", first)));
            blocks.add(heading("요청 메시지 명세"));
            blocks.add(table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")));
            blocks.add(heading("응답 메시지 명세"));
            blocks.add(table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")));
            blocks.add(heading("요청/응답 메시지 예제"));
            blocks.add(table(row("요청메시지"), row(example + "?serviceKey=KEY&_type=json")));
        }

        List<ParsedApi> apis = parse(blocks);

        assertEquals(first, url(apis, 0));
        assertEquals("https://apis.data.go.kr/B551011/AreaTarDivService/areaIntlDivList", url(apis, 1));
    }

    @Test
    void 뒤_오퍼레이션의_Call_Back_URL을_베낀_앞_오퍼레이션도_요청_예제를_따른다() {
        // 한국식품연구원 전통식품정보: 앞 기능의 Call Back URL에 뒤 기능 주소를 적었다. 게이트웨이에는 예제 주소만 있다
        String culture = "http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodHistoryCultureList";
        List<Block> blocks = new ArrayList<>();
        for (String example : List.of("getFoodHistoryList", "getFoodCultureList")) {
            blocks.add(heading("상세기능정보"));
            blocks.add(table(row("Call Back URL", culture)));
            blocks.add(heading("요청 메시지 명세"));
            blocks.add(table(HEADER, row("foodCd", "식품코드", "6", "1", "103457", "식품코드")));
            blocks.add(heading("응답 메시지 명세"));
            blocks.add(table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드")));
            blocks.add(heading("요청/응답 메시지 예제"));
            blocks.add(table(row("요청메시지"),
                    row("http://apis.data.go.kr/B551553/TradFoodInfoService/" + example + "?ServiceKey=키&foodCd=103457")));
        }

        List<ParsedApi> apis = parse(blocks);

        assertEquals("http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodHistoryList", url(apis, 0));
        assertEquals("http://apis.data.go.kr/B551553/TradFoodInfoService/getFoodCultureList", url(apis, 1),
                "둘 다 같은 주소를 적었으면 각자 예제를 따른다");
    }

    @Test
    void 서비스_URL이_오퍼레이션_경로까지_담으면_이름을_다시_붙이지_않는다() {
        // 한국전력거래소: 서비스 URL이 이미 …/sumperfuel5m/getSumperfuel5m 이다
        List<ParsedApi> apis = parse(List.of(
                table(row("서비스 URL", "운영환경", "https://openapi.kpx.or.kr/openapi/sumperfuel5m/getSumperfuel5m")),
                heading("상세기능정보"),
                table(row("오퍼레이션명(영문)", "getSumperfuel5m"), row("Call Back URL", "N/A")),
                heading("요청 메시지 명세"),
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")),
                heading("응답 메시지 명세"),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"))));

        assertEquals("https://openapi.kpx.or.kr/openapi/sumperfuel5m/getSumperfuel5m", url(apis, 0));
    }

    @Test
    void 서비스_개요_표는_앞_오퍼레이션을_앞_서비스_주소로_마무리한다() {
        // 국토교통부 토지이음: 정보 표 없이 서비스마다 개요 표의 서비스Url이 오퍼레이션 주소다
        List<Block> blocks = new ArrayList<>();
        for (String service : List.of("DTarLandUseInfo", "DTsearchLunCd")) {
            blocks.add(table(row("서비스명", service),
                    row("서비스Url", "http://apis.data.go.kr/1613000/arLandUseInfoService/" + service)));
            blocks.add(table(row("구분", "코드", "", "코드명", "샘플데이터"),
                    row("In", "serviceKey", "", "인증키", ""),
                    row("\\Out", "response", "", "결과", ""),
                    row("", "", "resultCode", "결과코드", "")));
        }

        List<ParsedApi> apis = parse(blocks);

        assertEquals(List.of("/DTarLandUseInfo", "/DTsearchLunCd"),
                List.of(api(apis, 0).get("endpoint").asText(), api(apis, 1).get("endpoint").asText()));
        assertEquals(List.of("resultCode"), leafNames(api(apis, 0)), "오타(\\Out)가 붙은 구분 값도 응답이다");
    }

    @Test
    void 응답_변형_표가_여럿이면_같은_이름의_필드를_한번만_담는다() {
        // 국립해양조사원 바다낚시지수: "(갯바위)"와 "(선상)" 응답 표가 공통 필드를 되풀이한다
        List<ParsedApi> apis = parse(standard(
                table(HEADER, row("serviceKey", "인증키", "100", "1", "키", "인증키")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("seafsPstnNm", "포인트명", "50", "0", "구룡포", "포인트명")),
                table(HEADER, row("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                        row("seafsTgfshNm", "대상어종", "50", "0", "참돔", "대상어종"))));

        assertEquals(List.of("resultCode", "seafsPstnNm", "seafsTgfshNm"), leafNames(api(apis, 0)));
    }

    /** 정보/요청/응답 구간을 갖춘 표준 문서에 요청·응답 표만 바꿔 끼운다 */
    private List<Block> standard(Block.Table request, Block.Table response, Block.Table... more) {
        List<Block> blocks = new ArrayList<>(List.of(
                heading("가. 상세기능정보"),
                table(row("Call Back URL", "http://apis.data.go.kr/1234567/TestSvc/getTestList")),
                heading("나. 요청 메시지 명세"),
                request,
                heading("다. 응답 메시지 명세"),
                response));
        blocks.addAll(List.of(more));
        return blocks;
    }

    private List<ParsedApi> parse(List<Block> blocks) {
        return new SpecBlockAssembler().parse(blocks, "layout.hwp", "HWP");
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

    private JsonNode api(List<ParsedApi> apis, int index) {
        assertTrue(apis.size() > index, "오퍼레이션 수: " + apis.size());
        return apis.get(index).ir().get("api");
    }

    private String url(List<ParsedApi> apis, int index) {
        JsonNode api = api(apis, index);
        return api.get("baseUrl").asText() + api.get("endpoint").asText();
    }

    private List<String> names(JsonNode nodes, String key) {
        List<String> names = new ArrayList<>();
        nodes.forEach(node -> names.add(node.get(key).asText()));
        return names;
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

    private boolean hasField(JsonNode api, String path) {
        return leafNames(api).size() > 0 && api.get("responseFields").findValuesAsText("path").contains(path);
    }
}
