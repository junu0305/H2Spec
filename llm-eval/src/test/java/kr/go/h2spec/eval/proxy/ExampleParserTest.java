package kr.go.h2spec.eval.proxy;

import kr.go.h2spec.eval.proxy.ExampleParser.Format;
import kr.go.h2spec.eval.proxy.ExampleParser.Parsed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 문서에 적힌 응답 예제를 구조로 읽기, 그리고 표준 구조 판정. 예시 글은 corpus·샘플 문서에 실제로 나온 모양을 줄였다.
 */
class ExampleParserTest {

    @Test
    void 둥근_따옴표와_말줄임이_섞인_JSON의_키와_중첩을_읽는다() {
        // nps의 응답 예제는 키를 모두 둥근 따옴표로 감쌌고, kosha는 "Y“처럼 여닫는 따옴표가 다르다
        Parsed parsed = ExampleParser.parse("""
                {
                  “response”: {
                    “header”: {“resultCode”: “00”, “resultMsg”: “NORMAL_CODE”},
                    “body”: {
                      “items”: {“item”: [{“dataCrtYm”: “201710”, "docAuthYn":"Y“ }, ......]},
                      “totalCount”: 2
                    }
                  }
                }""");

        assertEquals(Format.JSON, parsed.format());
        ExampleNode item = parsed.document().children().get("response").children().get("body").children()
                .get("items").children().get("item");
        assertTrue(item.repeated());
        assertEquals(List.of("dataCrtYm", "docAuthYn"), List.copyOf(item.children().keySet()));
        assertEquals("Y", item.children().get("docAuthYn").text());
        assertNull(StandardStructure.violation(parsed.document()));
    }

    @Test
    void 최상위_키가_여럿인_JSON은_루트가_여럿이다() {
        Parsed parsed = ExampleParser.parse("""
                {
                  "totCnt": 13,
                  "hasMore": true,
                  "data": [ { "baseDate": "20190704", "userCount": "4" }, … ]
                }""");

        assertEquals(List.of("totCnt", "hasMore", "data"), List.copyOf(parsed.document().children().keySet()));
        assertEquals("13", parsed.document().children().get("totCnt").text());
        assertEquals("루트가 response 하나가 아님: totCnt, hasMore, data",
                StandardStructure.violation(parsed.document()));
    }

    @Test
    void 예제_앞의_설명과_접힌_트리_표시와_XML_선언을_건너뛰고_읽는다() {
        // msrstn-info: "1.1 이전 버전" 다음 줄부터 예제, kdic: 브라우저에서 복사한 "-<response>"
        Parsed parsed = ExampleParser.parse("""
                1.1 이전 버전
                <?xml version="1.0" encoding="UTF-8"?>
                -<response>
                -<header><resultCode>00</resultCode><resultMsg>NORMAL_CODE</resultMsg></header>
                -<body xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                -<items>
                -<item><dmX>37.57</dmX><item>SO2, CO</item><coFlag/></item>
                -<item><dmX>37.58</dmX><item>PM10</item><coFlag/></item>
                </items>
                <numOfRows>10</numOfRows>
                </body>
                </response>""");

        assertEquals(Format.XML, parsed.format());
        ExampleNode item = parsed.document().children().get("response").children().get("body").children()
                .get("items").children().get("item");
        assertTrue(item.repeated(), "같은 이름의 형제는 반복이다");
        assertEquals(List.of("dmX", "item", "coFlag"), List.copyOf(item.children().keySet()));
        assertTrue(item.children().get("item").isLeaf(), "item 안의 item은 값 필드다");
        assertNull(StandardStructure.violation(parsed.document()));
    }

    @Test
    void 글_안에_마크업을_그대로_적은_값은_하위_구조로_보지_않는다() {
        // KCI 참고문헌: TITLE 값에 JATS 마크업이 닫히지 않은 채 들어 있다
        Parsed parsed = ExampleParser.parse("""
                <response><header><resultCode>00</resultCode></header><body><items><item>
                <NUM>1</NUM>
                <TITLE>
                01"> <label>1</label> <element-citation publication-type="book"> <collab>경기도교육청</collab> <source xml:lang="ko">통계자료.
                </TITLE>
                <TITLESUB/>
                <resultMsg>NORMAL<br>SERVICE</resultMsg>
                </item></items></body></response>""");

        ExampleNode item = parsed.document().children().get("response").children().get("body").children()
                .get("items").children().get("item");
        assertTrue(item.children().get("TITLE").isLeaf());
        assertTrue(item.children().get("resultMsg").isLeaf());
        assertNull(StandardStructure.violation(parsed.document()));
    }

    @Test
    void 예제가_아닌_글은_읽지_않는다() {
        // 설명 문장 속 태그 이름, 주소의 자리 표시, AJAX 샘플 소스(HTML), 루트 하나뿐인 조각
        assertNull(ExampleParser.parse("응답 메세지 중 <dataTime>이 2022-12-19 05시 발표일 경우 <informData> 2022-12-19"));
        assertNull(ExampleParser.parse(
                "https://open.jejudatahub.net/api/proxy/7ttt/{your_appkey}?{params(key=value)}"));
        assertNull(ExampleParser.parse("""
                <link href="http://api.nongsaro.go.kr/css/api.css" rel="stylesheet" type="text/css">
                <script type="text/javascript">nongsaroOpenApiRequest.apiKey = "발급받은인증키";</script>"""));
        assertNull(ExampleParser.parse("<response></response>"));
        assertNull(ExampleParser.parse("ServiceKey=서비스키&certNo=원본문서확인번호"));
    }

    @Test
    void 예제를_여러_줄로_나눠_적었는지_알아본다() {
        assertTrue(ExampleParser.startsExample("  <response>"));
        assertTrue(ExampleParser.startsExample("-<response>"));
        assertTrue(ExampleParser.startsExample("{"));
        assertTrue(ExampleParser.continuesExample("\"resultCode\": \"00\","));
        assertTrue(ExampleParser.continuesExample("……"));
        assertEquals(false, ExampleParser.continuesExample("2. OpenAPI 에러 코드정리"));
    }

    @Test
    void 표준_구조와_다른_자리를_알려준다() {
        // its-traffic-light: page가 body 밖 response 아래에 있다
        assertEquals("response 아래에 header·body가 아닌 page이(가) 있음", violation("""
                <response><header><resultCode>00</resultCode></header><body><items><item><no>1</no></item></items>
                </body><page><page>10</page><countPerPage>1</countPerPage></page></response>"""));
        // 대기오염정보 실시간 측정정보: items 없이 body 바로 아래에 item
        assertEquals("body 아래에 items가 아닌 컨테이너 item이(가) 있음", violation("""
                <response><header><resultCode>00</resultCode></header><body><item><so2Grade>1</so2Grade></item>
                (중략)<item><so2Grade>2</so2Grade></item></body></response>"""));
        // jeju-folksong: 기관 고유 루트
        assertEquals("루트가 response 하나가 아님: jejunetApi", violation("""
                <jejunetApi><resultCode>00</resultCode><items><item><seq>59</seq></item></items></jejunetApi>"""));
        assertEquals("item 아래에 하위 항목을 가진 detail이(가) 있음", violation("""
                {"response": {"body": {"items": {"item": [{"detail": {"name": "a"}}]}}}}"""));
        assertEquals("header 아래에 하위 항목을 가진 meta이(가) 있음", violation("""
                {"response": {"header": {"meta": {"code": "00"}}}}"""));
    }

    @Test
    void JSON에서_items가_바로_배열인_모양도_표준이다() {
        // 생성 DTO는 표준 경로 하나로 items.item[]과 items[] 두 모양을 다 받는다 (설계 4.2)
        assertNull(violation("""
                {"response": {"header": {"resultCode": "00"}, "body": {"items": [{"stationName": "종로구"}],
                 "totalCount": 1}}}"""));
        assertNull(violation("""
                {"response": {"header": {"resultCode": "00"}, "body": {"items": "", "totalCount": 0}}}"""));
    }

    private static String violation(String example) {
        return StandardStructure.violation(ExampleParser.parse(example).document());
    }
}
