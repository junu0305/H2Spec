package kr.go.h2spec.parser.llm.normalize;

import kr.go.h2spec.parser.llm.schema.LlmSuccessCode.Source;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.SuccessCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 성공코드 정책. 예시는 실제 기관 문서에 나온 후보다. */
class SuccessCodeSelectorTest {

    @Test
    void 모든_자리가_0인_숫자_후보는_00으로_맞추고_후보를_노트로_남긴다() {
        // 기상청 중기예보: 표 00, 예제 0
        SuccessCodeSelector.Selection kma = select(code("00", Source.FIELD_TABLE, 1), code("0", Source.RESPONSE_EXAMPLE, 2));
        assertEquals("00", kma.code());
        assertEquals("성공코드 후보: 00(응답 필드 표), 0(응답 예제) → 00 (모든 자리가 0인 코드는 00으로 맞춤)", kma.note());

        // 보건복지부: 표 0, 예제 00
        assertEquals("00", select(code("0", Source.FIELD_TABLE, 1), code("00", Source.RESPONSE_EXAMPLE, 2)).code());

        // 국립해양조사원: 후보가 0뿐이어도 00이고, 바꿨으므로 알린다
        SuccessCodeSelector.Selection khoa = select(code("0", Source.FIELD_TABLE, 1));
        assertEquals("00", khoa.code());
        assertEquals("성공코드 후보: 0(응답 필드 표) → 00 (모든 자리가 0인 코드는 00으로 맞춤)", khoa.note());

        assertEquals("00", select(code("000", Source.ERROR_TABLE, 1)).code());
    }

    @Test
    void 남은_값이_여럿이면_응답_예제_응답_필드_표_에러코드_표_순으로_고른다() {
        // 선거관리위원회 개표소: 표 00, 예제 INFO-00. 표만 따르면 정상 응답이 모두 예외가 될 수 있다
        SuccessCodeSelector.Selection nec = select(code("00", Source.FIELD_TABLE, 1),
                code("INFO-00", Source.RESPONSE_EXAMPLE, 2));
        assertEquals("INFO-00", nec.code());
        assertEquals("성공코드 후보: 00(응답 필드 표), INFO-00(응답 예제) → INFO-00 "
                + "(응답 예제 > 응답 필드 표 > 에러코드 표 순으로 고름)", nec.note());

        assertEquals("OK", select(code("00", Source.ERROR_TABLE, 1), code("OK", Source.FIELD_TABLE, 9)).code());
    }

    @Test
    void 같은_출처_안에서는_문서에_먼저_나온_것을_쓴다() {
        assertEquals("A-00", select(code("B-00", Source.RESPONSE_EXAMPLE, 20), code("A-00", Source.RESPONSE_EXAMPLE, 10)).code());
    }

    @Test
    void 후보가_모두_같으면_노트를_남기지_않는다() {
        SuccessCodeSelector.Selection same = select(code("00", Source.FIELD_TABLE, 1), code("00", Source.RESPONSE_EXAMPLE, 2));
        assertEquals("00", same.code());
        assertNull(same.note());
        assertFalse(same.defaulted());

        assertNull(select(code("INFO-00", Source.RESPONSE_EXAMPLE, 1)).note());
    }

    @Test
    void 후보가_없으면_00을_넣고_기본값으로_채웠다고_알린다() {
        SuccessCodeSelector.Selection none = SuccessCodeSelector.select(List.of());

        assertEquals("00", none.code());
        assertTrue(none.defaulted());
        assertNull(none.note());
    }

    private SuccessCodeSelector.Selection select(SuccessCode... candidates) {
        return SuccessCodeSelector.select(List.of(candidates));
    }

    private SuccessCode code(String code, Source source, int position) {
        return new SuccessCode(code, source, position);
    }
}
