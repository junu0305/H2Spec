package kr.go.h2spec.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 확장자 → Reader·sourceFormat 대응. CLI는 여기서 명세 문서가 아니라고 본 파일을 모두 IR JSON으로 읽으므로,
 * 지금까지 CLI가 문서로 보던 확장자(.docx, .hwp, .hwpx. 대소문자 무시)와 정확히 같아야 한다.
 */
class DocumentReadersTest {

    @Test
    void 확장자마다_Reader와_sourceFormat을_정한다() {
        assertFormat("spec.docx", "DOCX", DocxSpecReader.class);
        assertFormat("spec.hwp", "HWP", HwpSpecReader.class);
        assertFormat("spec.hwpx", "HWPX", HwpxSpecReader.class);
    }

    @Test
    void 확장자의_대소문자는_가리지_않는다() {
        assertFormat("SPEC.DOCX", "DOCX", DocxSpecReader.class);
        assertFormat("명세서.Hwp", "HWP", HwpSpecReader.class);
        assertFormat("spec.hWpX", "HWPX", HwpxSpecReader.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"schema-example.json", "readme.txt", "spec.doc", "spec.docx.bak", "spec.hwp.json",
            "docx", "spec.", "spec"})
    void 그_밖의_파일은_명세_문서가_아니다(String fileName) {
        Path path = Path.of("input", fileName);

        assertFalse(DocumentReaders.isDocument(path));
        assertTrue(DocumentReaders.find(path).isEmpty());
    }

    @Test
    void 디렉터리_이름이_아니라_파일_이름의_확장자를_본다() {
        assertTrue(DocumentReaders.isDocument(Path.of("ir.json", "spec.hwp")));
        assertFalse(DocumentReaders.isDocument(Path.of("specs.docx", "schema-example.json")));
        assertFalse(DocumentReaders.isDocument(Path.of("/")), "파일 이름이 없는 경로");
    }

    @Test
    void 고른_Reader가_그_형식의_문서를_읽는다() throws Exception {
        Path hwp = Path.of(getClass().getResource("/docs/msrstn-info.hwp").toURI());

        DocumentReader reader = DocumentReaders.find(hwp).orElseThrow().reader();

        assertEquals(new HwpSpecReader().read(hwp), reader.read(hwp));
    }

    private void assertFormat(String fileName, String sourceFormat, Class<? extends DocumentReader> readerType) {
        Path path = Path.of("input", fileName);

        DocumentReaders.Format format = DocumentReaders.find(path).orElseThrow(() -> new AssertionError(fileName));

        assertEquals(sourceFormat, format.sourceFormat(), fileName);
        assertInstanceOf(readerType, format.reader(), fileName);
        assertTrue(DocumentReaders.isDocument(path), fileName);
    }
}
