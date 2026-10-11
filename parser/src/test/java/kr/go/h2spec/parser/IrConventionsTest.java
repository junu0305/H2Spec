package kr.go.h2spec.parser;

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
    void 다른_후보가_없으면_서비스_URL을_쓰되_공통_구간은_오퍼레이션_주소를_얻지_않는다() {
        List<String> notes = new ArrayList<>();
        assertEquals(SERVICE, endpoint(new UrlCandidates(null, null, null, null, null, SERVICE, null, false), notes));
        assertEquals(List.of("오퍼레이션 주소가 없어 서비스 URL을 그대로 오퍼레이션 주소로 씀"), notes);

        List<String> commonNotes = new ArrayList<>();
        assertNull(endpoint(new UrlCandidates(null, null, null, null, null, SERVICE, null, true), commonNotes));
        assertEquals(List.of(), commonNotes);
    }

    private String endpoint(UrlCandidates candidates, List<String> notes) {
        return IrConventions.endpointUrl(candidates, notes);
    }
}
