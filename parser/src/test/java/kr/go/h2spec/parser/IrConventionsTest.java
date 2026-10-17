package kr.go.h2spec.parser;

import kr.go.h2spec.generator.emit.ClientEmitter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 두 엔진이 같이 쓰는 IR 규칙을 공개 진입점으로 확인한다. llm 엔진은 표의 행 없이 칸의 원래 글과
 * 주소 후보만으로 이 함수들을 부르므로, 여기서도 그렇게 부른다. 예시는 실제 기관 문서에서 모았다.
 */
class IrConventionsTest {

    private static final String SERVICE = "http://apis.data.go.kr/B551011/WellnessTursmService";

    @Test
    void 포맷_파라미터_이름은_generator의_ClientEmitter와_같다() {
        // 두 목록이 어긋나면 클라이언트는 포맷 파라미터를 보내지 않는데 DTO는 그 포맷을 기대해 응답을 읽지 못한다
        assertEquals(ClientEmitter.responseFormatParameters(), IrConventions.responseFormatParameters());
    }

    @Test
    void 호스트만_있거나_baseUrl에_경로_변수가_남는_주소는_나누지_않고_이유를_돌려준다() {
        // 호스트를 오퍼레이션으로 나누면 baseUrl이 "http:/"인 IR이 나오고 generator도 받아 버린다
        IrConventions.UrlParts hostOnly = IrConventions.splitUrl("http://apis.data.go.kr");
        IrConventions.UrlParts hostVariable = IrConventions.splitUrl("http://{host}/svc/getList");
        IrConventions.UrlParts trailing = IrConventions.splitUrl(SERVICE + "/getList/{id}");

        assertFalse(hostOnly.ok());
        assertEquals("Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다: http://apis.data.go.kr", hostOnly.error());
        assertFalse(IrConventions.splitUrl("http://apis.data.go.kr/").ok());
        assertFalse(hostVariable.ok());
        assertEquals("경로 변수가 endpoint가 아니라 baseUrl에 있습니다: http://{host}/svc/getList", hostVariable.error());
        assertEquals(SERVICE, trailing.baseUrl());
        assertEquals("/getList/{id}", trailing.endpoint());
        assertEquals("List", trailing.apiId());
    }

    @Test
    void 패키지_이름은_기본_로캘과_상관없이_소문자로_만든다() {
        // 터키어 로캘에서 "I".toLowerCase()는 점 없는 ı가 되어 같은 문서도 PC마다 다른 패키지와 경로가 된다
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("kr.go.h2spec.client.info", IrConventions.generatorHints("Info").get("targetPackage").asText());
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void 문서_라이브러리가_던진_RuntimeException도_문서를_읽지_못한_IOException으로_알린다() {
        DocumentReaders.Format broken = new DocumentReaders.Format("DOCX", document -> {
            throw new IllegalStateException("지원하지 않는 도형");
        });

        IOException e = assertThrows(IOException.class, () -> broken.read(Path.of("broken.docx")));

        assertEquals("문서를 해석하지 못했습니다 (IllegalStateException: 지원하지 않는 도형)", e.getMessage());
        assertTrue(e.getCause() instanceof IllegalStateException);
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
