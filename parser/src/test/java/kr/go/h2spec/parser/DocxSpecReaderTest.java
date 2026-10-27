package kr.go.h2spec.parser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DOCX를 읽는 POI가 문서에 든 개체의 스키마를 갖췄는지 본다. 문서는 OOXML 부품을 직접 묶어 가장 작게 만든다.
 */
class DocxSpecReaderTest {

    private static final String W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final String R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    @TempDir
    Path tempDir;

    @Test
    void 머리글에_VML_도형이_든_DOCX도_읽는다() throws Exception {
        // 전주시 동물판매업소현황 OpenAPI 활용가이드: 머리글과 바닥글에 글상자를 품은 <v:polyline>이 있다. POI는 run 속 그림의
        // 글자를 찾으며 이 도형으로 내려가는데, poi-ooxml-lite에는 그 스키마가 없어 SchemaTypeLoaderException으로 문서 전체를 읽지 못했다
        Path docx = writeDocx(tempDir.resolve("vml-header.docx"), """
                <w:hdr xmlns:w="%s" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office">\
                <w:p><w:r><w:pict><v:polyline o:spid="_x0000_s2050" points="0,0 10,10"><v:textbox><w:txbxContent>\
                <w:p><w:r><w:t>OpenAPI 활용가이드</w:t></w:r></w:p></w:txbxContent></v:textbox></v:polyline></w:pict></w:r></w:p>\
                </w:hdr>""".formatted(W));

        List<Block> blocks = new DocxSpecReader().read(docx);

        assertEquals("본문", ((Block.Heading) blocks.get(0)).text());
    }

    private static Path writeDocx(Path out, String headerXml) throws IOException {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\
                <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\
                <Default Extension="xml" ContentType="application/xml"/>\
                <Override PartName="/word/document.xml" \
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\
                <Override PartName="/word/header1.xml" \
                ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>\
                </Types>""");
        parts.put("_rels/.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="%s/officeDocument" Target="word/document.xml"/></Relationships>"""
                .formatted(R));
        parts.put("word/_rels/document.xml.rels", """
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\
                <Relationship Id="rId1" Type="%s/header" Target="header1.xml"/></Relationships>"""
                .formatted(R));
        parts.put("word/document.xml", """
                <w:document xmlns:w="%s" xmlns:r="%s"><w:body><w:p><w:r><w:t>본문</w:t></w:r></w:p>\
                <w:sectPr><w:headerReference w:type="default" r:id="rId1"/></w:sectPr></w:body></w:document>"""
                .formatted(W, R));
        parts.put("word/header1.xml", headerXml);
        try (OutputStream file = Files.newOutputStream(out); ZipOutputStream zip = new ZipOutputStream(file)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out;
    }
}
