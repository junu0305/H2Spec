package kr.go.h2spec.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 기관마다 다른 필드 표 머리행을 표준 열로 맞추는지 본다. 라벨 예시는 실제 문서에서 모았다. */
class FieldColumnsTest {

    @Test
    void 표준_머리행을_표준_열_순서로_옮긴다() {
        FieldColumns columns = detect("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");

        assertEquals(List.of("stationName", "측정소명", "30", "1", "종로구", "측정소 이름", ""),
                columns.canonical(List.of("stationName", "측정소명", "30", "1", "종로구", "측정소 이름")));
    }

    @Test
    void 기관별_라벨_변형을_알아본다() {
        assertNotNull(detect("요청변수", "설명", "필수 여부"), "농촌진흥청 농사로");
        assertNotNull(detect("응답변수", "설명"), "두 칸짜리 표도 칸이 모두 라벨이면 머리행이다");
        assertNotNull(detect("Name", "Description", "Type", "Note"), "제주데이터허브");
        assertNotNull(detect("항목명", "타입", "필수", "값 샘플", "항목설명"), "제주특별자치도청");
        assertNotNull(detect("순번", "변수명", "", "변수설명", "항목크기", "필수여부", "비고"), "한국예탁결제원");
        assertNotNull(detect("항목명(영문)", "항목명(국문)", "항목크기(bytes)", "항목구분*", "샘플데이터", "항목 설명"));
    }

    @Test
    void 머리행이_아닌_행은_받아들이지_않는다() {
        assertNull(detect("코드구분", "코드", "코드명"), "코드표");
        assertNull(detect("에러코드", "에러메시지", "설명"), "에러 코드표");
        assertNull(detect("serviceKey", "인증키", "100", "1", "키", "설명"), "데이터 행");
        assertNull(detect("버전", "변경일", "변경사유", "변경내용"), "개정 이력");
    }

    @Test
    void 설명_라벨이_둘이면_앞의_것이_국문명이다() {
        // "항목명 | 항목설명 | 항목구분 | 샘플데이터 | 항목설명"
        FieldColumns columns = detect("항목명", "항목설명", "항목구분", "샘플데이터", "항목설명");

        List<String> row = columns.canonical(List.of("sido", "시도명", "1", "경기도", "시도 이름"));
        assertEquals("시도명", row.get(FieldColumns.KOR_NAME));
        assertEquals("시도 이름", row.get(FieldColumns.DESCRIPTION));
    }

    @Test
    void 비고는_설명이_따로_있으면_쓰지_않는다() {
        FieldColumns columns = detect("Name", "Description", "Type", "Note");

        List<String> row = columns.canonical(List.of("number", "페이지 번호", "integer", "default=1"));
        assertEquals("페이지 번호", row.get(FieldColumns.DESCRIPTION));
        assertEquals("integer", row.get(FieldColumns.TYPE));
    }

    @Test
    void 이름_칸의_트리_표지와_덧붙인_설명을_뗀다() {
        assertEquals("title", FieldColumns.cleanName("└ title"));
        assertEquals("numOfRows", FieldColumns.cleanName("numOfRows\n(한 페이지 결과 수)"));
        assertEquals("numOfRows", FieldColumns.cleanName("numOfRows(한 페이지 결과 수)"));
        assertEquals("mnab3Mnud4DlpnCnt", FieldColumns.cleanName("mnab3Mnud4DlpnCnt>"), "오타로 남은 꺾쇠");
        assertEquals("TEST_EVAL_ISUE_DD", FieldColumns.cleanName("TEST_EVAL_IS\nUE_DD"), "칸 폭 때문에 꺾인 식별자");
        assertEquals("pageNo", FieldColumns.cleanName("pageNo\n1"), "둘째 줄이 숫자면 이어 붙이지 않는다");
        assertEquals("MAX_WAIT_CONS_PWR", FieldColumns.cleanName("MAX_WAIT_\nCONS_\nPWR"), "세 줄로 꺾인 식별자");
        assertEquals("pm25Value", FieldColumns.cleanName("pm\n25Value"), "숫자로 시작하는 줄도 식별자 조각이다");
        assertEquals("ndrgItmIcsnTpCd", FieldColumns.cleanName("ndrgItmIcsn TpCd"), "공백으로 갈라진 낙타 표기");
        assertEquals("relevant departments", FieldColumns.cleanName("relevant departments"), "문서가 적은 띄어쓴 이름");
    }

    @Test
    void 설명의_필수_표지로_필수_여부를_정한다() {
        // 필수 여부 열이 없는 제주데이터허브 표는 설명에 "(필수)"를 붙인다
        FieldColumns columns = detect("Name", "Description", "Type", "Note");

        assertEquals("1", columns.canonical(List.of("searchDate", "분석일(필수)", "string", "YYYYMMDD"))
                .get(FieldColumns.REQUIRED));
        assertEquals("", columns.canonical(List.of("city", "시군구", "string", "")).get(FieldColumns.REQUIRED));
    }

    @Test
    void 들여쓴_이름을_찾고_깊이를_잰다() {
        FieldColumns columns = detect("항목명(영문)", "", "", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
        List<String> child = List.of("", "", "locdate", "날짜", "8", "1", "20190301", "날짜");

        assertEquals("locdate", columns.canonical(child).get(FieldColumns.NAME));
        assertEquals(2, columns.depth(child));
        assertEquals(1, columns.depth(List.of("└ title", "", "", "제목", "", "", "", "")));
    }

    private FieldColumns detect(String... labels) {
        return FieldColumns.detect(List.of(labels));
    }
}
