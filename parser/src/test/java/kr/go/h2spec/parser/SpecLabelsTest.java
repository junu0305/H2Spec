package kr.go.h2spec.parser;

import kr.go.h2spec.parser.SpecLabels.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 기관마다 다르게 적는 구간 제목과 주소 표기를 판별하는지 본다. 예시는 실제 문서에서 모았다. */
class SpecLabelsTest {

    @Test
    void 구간_제목의_표기_변형을_알아본다() {
        for (String text : List.of("다. 요청 메시지 명세", "요청메시지 명세", "- 요청메세지 명세", "(가) 요청 메시지 명세",
                "● 요청 변수(Request Parameters)", "Request Parameters", "□ 평균 해류도 오퍼레이션 요청 메시지 명세",
                "입력값 명세")) {
            assertEquals(Section.REQUEST, SpecLabels.headingSection(text), text);
        }
        for (String text : List.of("라. 응답 메시지 명세", "- 응답 메세지 명세", "● 응답 결과(Response Element)",
                "Response Body", "응답메시지(ResponseMessage)", "출력값 명세", "응답 메시지 명세(결과코드 포함)")) {
            assertEquals(Section.RESPONSE, SpecLabels.headingSection(text), text);
        }
        for (String text : List.of("나. 상세기능 정보", "a) 상세기능정보", "(1) 선거구별 선거인수 정보 조회 오퍼레이션 명세",
                "[금요일에과학터치 VOD통계] 상세기능명세")) {
            assertEquals(Section.INFO, SpecLabels.headingSection(text), text);
        }
        for (String text : List.of("마. 요청/응답 메시지 예제", "- 요청-응답 메시지 예제", "□ 요청/응답 메세지 예제")) {
            assertEquals(Section.EXAMPLE, SpecLabels.headingSection(text), text);
        }
        assertEquals(Section.ERROR, SpecLabels.headingSection("2. OpenAPI 에러 코드정리"));
        assertNull(SpecLabels.headingSection("※ 항목구분 : 필수(1), 옵션(0), 1건 이상 복수건(1..n)"), "주석");
        assertNull(SpecLabels.headingSection("공공데이터포털에서 발급받은 인증키를 요청 메시지 명세에 맞추어 넣어 호출하는 방법은 아래와 같으며 자세한 내용은 활용가이드를 참고하십시오"),
                "본문 문장");
    }

    @Test
    void 목차_항목을_알아본다() {
        assertTrue(SpecLabels.isTocEntry("다. 요청 메시지 명세\t5"));
        assertTrue(SpecLabels.isTocEntry("1.1.3 오퍼레이션 내역..........5"));
        assertFalse(SpecLabels.isTocEntry("다. 요청 메시지 명세"));
    }

    @Test
    void 주소_표지와_주소를_꺼낸다() {
        assertEquals("http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInList",
                SpecLabels.labeledUrl("● 요청 URL : http://api.nongsaro.go.kr/service/exportDataZoomIn/exportDataZoomInList"));
        assertNull(SpecLabels.labeledUrl("ex>https://open.jejudatahub.net/api/proxy/abc/{your_appkey}?a=1"), "표지 없는 예제 줄");
        assertTrue(SpecLabels.isUrlLabel("요청 URL"));
        assertEquals("http://apis.data.go.kr/1192136/CoastStat_ver_21/getCoastStatInfo_ver_21",
                SpecLabels.firstUrl("http://apis.data.go.kr/1192136/CoastStat_ver_21/getCoastStatInfo_ver_21?ServiceKey=키&pageNo=1"));
        assertEquals("https://portal.kosha.or.kr/openapi/v1/koshagw",
                SpecLabels.firstUrl("ServiceKey=서비스키&pageNo=1https://portal.kosha.or.kr/openapi/v1/koshagw?SG_APIM=키"),
                "칸 중간의 주소");
    }

    @Test
    void 줄바꿈_없는_공백을_일반_공백으로_다듬는다() {
        // NBSP는 String.strip()이 지우지 않아 빈 샘플 칸을 값이 있는 칸으로 착각하게 한다
        assertEquals("", SpecLabels.cleanText("   "));
        assertEquals("목록 정보", SpecLabels.cleanText(" 목록 정보　"));
    }
}
