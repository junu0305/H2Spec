package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static kr.go.h2spec.parser.llm.SampleDocument.heading;
import static kr.go.h2spec.parser.llm.SampleDocument.row;
import static kr.go.h2spec.parser.llm.SampleDocument.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 원문 대조의 찾기 규칙(설계 5.1)을 하나씩 본다. 예시는 실제 기관 문서에서 모았다. */
class SourceTextTest {

    private static final Set<Integer> NO_EVIDENCE = Set.of();

    @Test
    void 원문은_칸_하나씩_보고_두_칸에_걸친_글은_찾지_않는다() {
        SourceText source = source(table(row("pageNo", "pageSize")));

        assertTrue(source.names("pageNo pageSize", false, NO_EVIDENCE).isEmpty());
        assertTrue(source.names("pageNopageSize", false, NO_EVIDENCE).isEmpty());
        assertEquals(1, source.names("pageSize", false, NO_EVIDENCE).size());
    }

    @Test
    void LLM이_옮긴_br과_세로줄_표기를_되돌려_원래_글과_비교한다() {
        SourceText source = source(table(row("xml/json\ndefault : xml", "a|b")));

        assertEquals("xml/json\ndefault", LlmText.restore("xml/json<br>default"));
        assertEquals(1, source.names(LlmText.restore("xml/json<br>default"), false, NO_EVIDENCE).size());
        assertTrue(source.names("xml/json<br>default", false, NO_EVIDENCE).isEmpty(), "되돌리지 않은 글은 맞지 않는다");
        assertEquals(1, source.names(LlmText.restore("a\\|b"), false, NO_EVIDENCE).size());
        assertEquals("", LlmText.restore(null));
    }

    @Test
    void 공백을_지운_글로_칸_폭_때문에_꺾인_값을_찾고_찾은_자리의_원래_글을_돌려준다() {
        SourceText source = source(table(row("TEST_EVAL_IS\nUE_DD", "pm\n25Value")));

        SourceText.Hit hit = source.names("TEST_EVAL_ISUE_DD", true, NO_EVIDENCE).get(0);

        assertEquals("TEST_EVAL_IS\nUE_DD", hit.span(), "LLM이 옮긴 글이 아니라 찾은 칸의 원래 글");
        assertEquals("TEST_EVAL_ISUE_DD", IrConventions.cleanName(hit.span()), "rule 엔진과 같은 이름 정리로 잇는다");
        assertEquals("pm25Value", IrConventions.cleanName(source.names("pm25Value", true, NO_EVIDENCE).get(0).span()));
    }

    @Test
    void 원래_글로_공백으로_나뉜_이름을_찾는다() {
        // 공백을 지운 글(pageNopageSize)에서는 pageNo 뒤가 p라 경계가 아니다
        SourceText source = source(table(row("pageNo pageSize")));

        assertEquals(1, source.names("pageNo", true, NO_EVIDENCE).size());
        assertEquals(1, source.names("pageSize", true, NO_EVIDENCE).size());
    }

    @Test
    void 이름과_코드는_앞뒤가_영문자_숫자_밑줄_붙임표가_아니어야_한다() {
        SourceText source = source(table(row("pageNo", "INFO-00", "code_1", "└ stationName", "numOfRows\n(한 페이지 결과 수)")));

        assertTrue(source.names("No", true, NO_EVIDENCE).isEmpty(), "다른 이름의 일부");
        assertTrue(source.names("00", true, NO_EVIDENCE).isEmpty(), "다른 코드의 일부");
        assertTrue(source.names("code", true, NO_EVIDENCE).isEmpty());
        assertEquals(1, source.names("INFO-00", true, NO_EVIDENCE).size());
        assertEquals(1, source.names("stationName", true, NO_EVIDENCE).size(), "트리 표지와 공백은 경계다");
        assertEquals("numOfRows", source.names("numOfRows", true, NO_EVIDENCE).get(0).span(), "줄바꿈은 경계다");
    }

    @Test
    void 대소문자를_구분한다() {
        SourceText source = source(table(row("ServiceKey")));

        assertTrue(source.names("serviceKey", true, NO_EVIDENCE).isEmpty());
        assertEquals(1, source.names("ServiceKey", true, NO_EVIDENCE).size());
    }

    @Test
    void 주소는_뒤만_보고_진짜_주소의_앞부분은_맞지_않는다() {
        SourceText source = source(table(row(
                "http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList4?serviceKey={인증키(URL Encode)}")));

        assertFalse(foundUrl(source, "http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList"),
                "진짜 주소(…/trafficLightList4)의 앞부분");
        assertFalse(foundUrl(source, "http://apis.data.go.kr/6500000/trafficLightService4"), "뒤의 /는 경계가 아니다");
        assertTrue(foundUrl(source, "http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList4"),
                "질의 문자열(?)은 경계다");
        assertTrue(foundUrl(source, "apis.data.go.kr/6500000/trafficLightService4/trafficLightList4"), "앞은 보지 않는다");
        assertTrue(foundUrl(source,
                        "http://apis.data.go.kr/6500000/trafficLightService4/trafficLightList4?serviceKey={인증키(URLEncode)}"),
                "LLM이 주소 안의 공백을 지웠어도 공백을 지운 글에서 찾는다");
    }

    @Test
    void 주소_뒤의_마침표와_쉼표는_그다음이_칸_끝이나_공백일_때만_경계다() {
        SourceText source = source(
                heading("요청 주소는 http://a.kr/svc/getList. 입니다"),
                heading("http://b.kr/svc/getList.do"),
                heading("http://c.kr/svc/getList,next"),
                heading("http://d.kr/svc/getList,"),
                heading("http://e.kr/svc/{key}"));

        assertTrue(foundUrl(source, "http://a.kr/svc/getList"), "문장 끝의 마침표");
        assertFalse(foundUrl(source, "http://b.kr/svc/getList"), "뒤에 글이 이어지면 주소의 일부(.do)다");
        assertFalse(foundUrl(source, "http://c.kr/svc/getList"));
        assertTrue(foundUrl(source, "http://d.kr/svc/getList"), "칸 끝의 쉼표");
        assertFalse(foundUrl(source, "http://e.kr/svc/"), "{는 경로 변수의 시작이라 경계가 아니다");
    }

    @Test
    void 응답_예제의_JSON_키는_네_가지_따옴표와_뒤의_쌍점으로_찾는다() {
        SourceText source = source(table(row(
                "{\n  \"totCnt\": 13,\n  “response”: {},\n  'hasMore' : true,\n  \"plain\",\n  \"data\": [\"value\"]\n}")));

        assertEquals(1, source.keys("totCnt", NO_EVIDENCE).size());
        assertEquals(1, source.keys("response", NO_EVIDENCE).size(), "워드프로세서가 바꾼 둥근 따옴표");
        assertEquals(1, source.keys("hasMore", NO_EVIDENCE).size(), "작은따옴표, 쌍점 앞의 공백");
        assertTrue(source.keys("plain", NO_EVIDENCE).isEmpty(), "뒤에 쌍점이 없으면 키가 아니다");
        assertTrue(source.keys("value", NO_EVIDENCE).isEmpty(), "값은 키가 아니다");
        assertTrue(source.keys("Cnt", NO_EVIDENCE).isEmpty());
    }

    @Test
    void XML_태그는_속성이_붙거나_빈_태그여도_찾는다() {
        SourceText source = source(table(row(
                "<items>\n<item seq=\"1\">\n<TITLESUB/>\n<NUM>1</NUM>\n</item>\n</items>")));

        assertEquals(1, source.keys("item", NO_EVIDENCE).size(), "속성이 붙은 태그");
        assertEquals(1, source.keys("TITLESUB", NO_EVIDENCE).size(), "빈 태그");
        assertEquals(1, source.keys("NUM", NO_EVIDENCE).size());
        assertEquals(1, source.keys("items", NO_EVIDENCE).size());
        assertTrue(source.keys("seq", NO_EVIDENCE).isEmpty(), "속성 이름은 태그가 아니다");
        assertTrue(source.keys("TITLE", NO_EVIDENCE).isEmpty(), "다른 태그의 앞부분");
    }

    @Test
    void 같은_글이_여러_칸에_있으면_칸_전체가_그_글인_곳_근거_블록_문서_순서로_고른다() {
        SourceText source = source(
                heading("요청 주소: http://a.kr/svc/getList"),
                table(row("http://a.kr/svc/getList?key=1")),
                table(row("http://a.kr/svc/getList")));

        assertEquals(List.of("T3", "T2", "P1"), blockIds(source.urls("http://a.kr/svc/getList", Set.of(2))));
        assertEquals(List.of("T3", "P1", "T2"), blockIds(source.urls("http://a.kr/svc/getList", NO_EVIDENCE)));
    }

    @Test
    void 영문_오퍼레이션명은_표_칸의_원래_글_전체가_그_이름일_때만_맞는다() {
        SourceText source = source(
                heading("getMsrstnList"),
                table(row("오퍼레이션명(영문)", "getMsrstnList(측정소 목록)")),
                table(row("오퍼레이션명(영문)", " getNearbyMsrstnList ")));

        assertTrue(source.operationNames("getMsrstnList", NO_EVIDENCE).isEmpty(), "문단과 설명이 붙은 칸은 보지 않는다");
        assertEquals(1, source.operationNames("getNearbyMsrstnList", NO_EVIDENCE).size(), "앞뒤 공백은 지운다");
    }

    @Test
    void 근거_블록_번호는_직렬화와_같은_공용_순번이고_종류가_맞아야_한다() {
        SourceText source = source(heading("a"), table(row("b")), heading("c"));

        assertEquals(Set.of(1, 2), source.evidenceBlocks(List.of("P1", "T2", "T3", "P9", "X1", "")));
        assertEquals(Set.of(), source.evidenceBlocks(null));
    }

    @Test
    void 원문_응답_예제의_루트는_칸_하나에_담긴_예제에서_찾는다() {
        SourceText source = source(
                table(row("{\n  \"totCnt\": 13,\n  \"data\": []\n}")),
                table(row("1.1 버전\n<?xml version=\"1.0\"?>\n<response><header><resultCode>00</resultCode></header></response>")),
                table(row("TITLE", "<label>1</label>")),
                table(row("<EMPTY />\n<other><x>1</x></other>")));

        assertEquals(List.of("totCnt", "response", "other"), source.responseExampleRoots(NO_EVIDENCE),
                "샘플 칸의 <label>1</label> 같은 값은 예제가 아니고, 빈 태그는 루트가 아니다");
        assertEquals(List.of("totCnt"), source.responseExampleRoots(Set.of(1)), "근거가 있으면 그 블록만 본다");
    }

    private SourceText source(Block... blocks) {
        return new SourceText(List.of(blocks));
    }

    private boolean foundUrl(SourceText source, String candidate) {
        return !source.urls(candidate, NO_EVIDENCE).isEmpty();
    }

    private List<String> blockIds(List<SourceText.Hit> hits) {
        return hits.stream().map(hit -> hit.cell().blockId()).toList();
    }
}
