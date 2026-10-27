package kr.go.h2spec.parser;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.header.ParaHeader;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.writer.HWPWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HWP 문단 안에는 줄바꿈(Shift+Enter)이 새 문단이 아니라 문단 내부의 문자 컨트롤(code 10)로 저장된다.
 * hwplib의 {@code ParaText#getNormalString}은 일반 글자만 남기고 이 컨트롤 문자를 조용히 버리므로,
 * 이를 그대로 썼다면 "1줄\n2줄"이 "1줄2줄"로 붙어버렸을 것이다. 실제로 읽었을 때 줄바꿈이
 * 보존되는지 직접 확인한다.
 */
class HwpSpecReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void 문단_내_줄바꿈_문자를_잃지_않는다() throws Exception {
        Path hwp = tempDir.resolve("line-break.hwp");
        buildSingleParagraphFile(hwp, "1줄", "2줄");

        List<Block> blocks = new HwpSpecReader().read(hwp);

        Block.Heading heading = (Block.Heading) blocks.get(0);
        assertEquals("1줄\n2줄", heading.text());
    }

    @Test
    void HWP_라이브러리가_읽지_못하면_원인을_메시지에_붙인다() throws Exception {
        // 메시지가 파일 이름만 담아 CLI에 "HWP 파일을 읽을 수 없습니다"만 찍혔다. 원인은 cause로만 붙어 사용자에게 보이지 않았다
        Path hwp = Files.writeString(tempDir.resolve("broken.hwp"), "HWP 문서가 아니다");

        IOException e = assertThrows(IOException.class, () -> new HwpSpecReader().read(hwp));

        Throwable cause = e.getCause();
        assertTrue(e.getMessage().startsWith("HWP 파일을 읽을 수 없습니다: broken.hwp"), e.getMessage());
        assertTrue(e.getMessage().contains("(" + cause.getClass().getSimpleName() + ": " + cause.getMessage() + ")"),
                e.getMessage());
    }

    @Test
    void HWP_3_0_문서는_지원하지_않는_형식이라고_알린다() throws Exception {
        // hwplib는 HWP 5.0(OLE 복합 파일)만 읽는다. HWP 3.0 문서는 "Invalid header signature" 같은 원인만으로는 알아보기 어렵다
        byte[] header = "HWP Document File V3.00 \u001a\u0001\u0002\u0003\u0004\u0005".getBytes(StandardCharsets.US_ASCII);
        Path hwp = Files.write(tempDir.resolve("old.hwp"), header);

        IOException e = assertThrows(IOException.class, () -> new HwpSpecReader().read(hwp));

        assertTrue(e.getMessage().contains("HWP 3.0"), e.getMessage());
        assertTrue(e.getMessage().contains("old.hwp"), e.getMessage());
    }

    private void buildSingleParagraphFile(Path out, String firstLine, String secondLine) throws Exception {
        HWPFile hwpFile = BlankFileMaker.make();
        Section section = hwpFile.getBodyText().getSectionList().get(0);
        Paragraph p = section.getParagraph(0);

        p.createText();
        ParaText text = p.getText();
        text.addString(firstLine);
        HWPCharControlChar lineBreak = text.addNewCharControlChar();
        lineBreak.setCode(10);
        text.addString(secondLine);

        p.createCharShape();
        p.getCharShape().addParaCharShape(0, 0);

        p.createLineSeg();
        LineSegItem lineSegItem = p.getLineSeg().addNewLineSegItem();
        lineSegItem.setLineHeight(1000);
        lineSegItem.setTextPartHeight(1000);
        lineSegItem.setDistanceBaseLineToLineVerticalPosition(850);
        lineSegItem.setLineSpace(300);
        lineSegItem.setSegmentWidth(42000);
        lineSegItem.getTag().setFirstSegmentAtLine(true);
        lineSegItem.getTag().setLastSegmentAtLine(true);

        ParaHeader header = p.getHeader();
        header.setCharacterCount(firstLine.length() + 1 + secondLine.length() + 1);

        HWPWriter.toFile(hwpFile, out.toString());
    }
}
