package kr.go.h2spec.parser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static kr.go.h2spec.parser.ReviewNotes.FieldNote.SAMPLE_INTEGER;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 같은 종류의 검수 노트를 한 줄로 묶는다. 기상청 중기예보 MidTa처럼 필드마다 같은 노트가 수십 개 붙으면
 * 그 사이의 다른 노트가 묻히기 때문이다.
 */
class ReviewNotesTest {

    @Test
    void 같은_종류의_필드_노트는_첫_노트_자리에_한_줄로_묶는다() {
        ReviewNotes notes = new ReviewNotes();
        notes.add("Call Back URL에 스킴이 없어 https://를 붙임: portal.kosha.or.kr/openapi/v1/koshagw");
        notes.addField(SAMPLE_INTEGER, "taMin4");
        notes.add("뒤에 나온 다른 노트");
        notes.addField(SAMPLE_INTEGER, "taMax4");

        assertEquals(List.of(
                "Call Back URL에 스킴이 없어 https://를 붙임: portal.kosha.or.kr/openapi/v1/koshagw",
                "샘플데이터 기반 integer 추론 (코드형 문자열일 수 있음, 2개): taMin4, taMax4",
                "뒤에 나온 다른 노트"), notes.lines());
    }

    @Test
    void 요청과_응답에_같은_이름이_있으면_한_번만_적고_개수는_적은_이름의_수다() {
        // 기업 재무정보: bizYear가 요청 파라미터와 응답 필드에 함께 있다
        ReviewNotes notes = new ReviewNotes();
        notes.addField(SAMPLE_INTEGER, "bizYear");
        notes.addField(SAMPLE_INTEGER, "enpSaleAmt");
        notes.addField(SAMPLE_INTEGER, "bizYear");

        assertEquals(List.of("샘플데이터 기반 integer 추론 (코드형 문자열일 수 있음, 2개): bizYear, enpSaleAmt"),
                notes.lines());
    }
}
