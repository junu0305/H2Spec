package kr.go.h2spec.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * convert가 명세 문서를 SpecParser로 변환할 때. 문서인지는 DocumentReaders가 확장자로 정하고,
 * 엔진이 문서를 변환하지 못한 실패(SpecParseException)는 기존 파서의 실패와 같은 문구와 종료 코드로 알린다.
 */
class ConvertCommandSpecParserTest {

    @TempDir
    Path tempDir;

    @Test
    void 대문자_확장자_문서도_명세_문서로_변환한다() throws Exception {
        Path docx = Files.copy(docPath("msrstn-info.docx"), tempDir.resolve("MSRSTN-INFO.DOCX"));
        Path output = tempDir.resolve("output");

        int exit = new CommandLine(new H2SpecCli()).execute(
                "convert", "--input", docx.toString(), "--output", output.toString());

        assertEquals(0, exit);
        JsonNode metadata = new ObjectMapper().readTree(output.resolve("ir/MsrstnList.json").toFile()).get("metadata");
        assertEquals("MSRSTN-INFO.DOCX", metadata.get("sourceFile").asText());
        assertEquals("DOCX", metadata.get("sourceFormat").asText());
        assertTrue(Files.exists(output.resolve("kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    @Test
    void 상세기능을_찾지_못한_문서는_파서의_메시지만_알리고_실패한다() throws Exception {
        Path docx = writeDocxWithoutSpec(tempDir.resolve("no-spec.docx"));
        Path output = tempDir.resolve("output");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", docx.toString(), "--output", output.toString());

        assertEquals(1, exit);
        assertEquals("문서에서 상세기능 명세를 찾지 못했습니다: no-spec.docx" + System.lineSeparator(), err.toString());
        assertEquals("", out.toString());
        assertFalse(Files.exists(output.resolve("ir")), "쓸 IR이 없다");
    }

    @Test
    void 디렉터리에서는_상세기능을_찾지_못한_문서만_실패로_세고_나머지는_변환한다() throws Exception {
        Path inputDir = Files.createDirectories(tempDir.resolve("input"));
        writeDocxWithoutSpec(inputDir.resolve("no-spec.docx"));
        Files.copy(docPath("msrstn-info.docx"), inputDir.resolve("msrstn-info.docx"));
        Path output = tempDir.resolve("output");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        CommandLine cli = new CommandLine(new H2SpecCli());
        cli.setOut(new PrintWriter(out));
        cli.setErr(new PrintWriter(err));

        int exit = cli.execute("convert", "--input", inputDir.toString(), "--output", output.toString());

        assertEquals(1, exit);
        assertTrue(err.toString().contains("문서에서 상세기능 명세를 찾지 못했습니다: no-spec.docx"), err.toString());
        assertTrue(out.toString().contains("2개 중 1개 변환 성공, 1개 실패"), out.toString());
        assertTrue(Files.exists(output.resolve("kr/go/h2spec/client/msrstnlist/MsrstnListClient.java")));
    }

    private Path docPath(String fileName) throws Exception {
        return Path.of(getClass().getResource("/docs/" + fileName).toURI());
    }

    /**
     * 문단 하나뿐이라 상세기능 명세가 없는 DOCX. cli는 POI를 직접 쓰지 않으므로 OOXML 패키지의 최소 구성을 손으로 묶는다.
     */
    private Path writeDocxWithoutSpec(Path out) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out))) {
            zipEntry(zip, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/word/document.xml" \
                    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
                    </Types>""");
            zipEntry(zip, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" \
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" \
                    Target="word/document.xml"/>
                    </Relationships>""");
            zipEntry(zip, "word/document.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body><w:p><w:r><w:t>개정 이력</w:t></w:r></w:p></w:body>
                    </w:document>""");
        }
        return out;
    }

    private void zipEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
