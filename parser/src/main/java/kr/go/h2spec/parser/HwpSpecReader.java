package kr.go.h2spec.parser;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HWP 파일을 읽어 문단/표를 문서 순서 그대로 {@link Block} 목록으로 변환한다.
 * <p>
 * HWP는 표를 별도 본문 요소가 아니라, 표가 놓인 문단의 컨트롤(문단에 딸린 부속 개체)로 담는다.
 * 그래서 DOCX처럼 본문 요소를 그대로 순회할 수 없고, 각 문단의 텍스트를 먼저 확인한 뒤
 * 그 문단에 표 컨트롤이 있으면 이어서 꺼내는 방식으로 순서를 맞춘다.
 */
public class HwpSpecReader {

    public List<Block> read(Path hwp) throws IOException {
        HWPFile hwpFile = readFile(hwp);

        List<Block> blocks = new ArrayList<>();
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (Paragraph paragraph : section) {
                addHeading(blocks, paragraph);
                addTables(blocks, paragraph);
            }
        }
        return blocks;
    }

    private HWPFile readFile(Path hwp) throws IOException {
        try {
            return HWPReader.fromFile(hwp.toFile());
        } catch (Exception e) {
            throw new IOException("HWP 파일을 읽을 수 없습니다: " + hwp.getFileName(), e);
        }
    }

    private void addHeading(List<Block> blocks, Paragraph paragraph) throws IOException {
        String text = text(paragraph).trim();
        if (!text.isEmpty()) {
            blocks.add(new Block.Heading(text));
        }
    }

    private void addTables(List<Block> blocks, Paragraph paragraph) throws IOException {
        if (paragraph.getControlList() == null) {
            return;
        }
        // control.getType()이 아니라 instanceof로 판별한다. hwplib의 ControlType.ctrlIdOf()는
        // 알 수 없는 컨트롤 id를 조용히 Table로 잘못 분류하고, FactoryForControl.create()는
        // 알 수 없는 컨트롤 id에 대해 null을 넣기도 한다 — getType() 비교 후 캐스팅하면
        // ClassCastException/NPE로 이어질 수 있다. DOCX 리더(instanceof XWPFTable)와도 같은 방식이다.
        for (Control control : paragraph.getControlList()) {
            if (control instanceof ControlTable table) {
                blocks.add(new Block.Table(readRows(table)));
            }
        }
    }

    private List<List<String>> readRows(ControlTable table) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        for (Row row : table.getRowList()) {
            List<String> cells = new ArrayList<>();
            for (Cell cell : row.getCellList()) {
                cells.add(cellText(cell));
                // 가로 병합된 칸은 하나로만 나오므로 나머지 폭을 빈 칸으로 채워
                // 다른 행과 열 위치를 맞춘다. 채우지 않으면 그 행만 열이 밀린다.
                for (int i = 1; i < colSpan(cell); i++) {
                    cells.add("");
                }
            }
            rows.add(cells);
        }
        return rows;
    }

    private int colSpan(Cell cell) {
        return cell.getListHeader() == null ? 1 : Math.max(1, cell.getListHeader().getColSpan());
    }

    private String cellText(Cell cell) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Paragraph paragraph : cell.getParagraphList()) {
            text.append(text(paragraph));
        }
        return text.toString().trim();
    }

    /**
     * 문단의 텍스트를 추출한다. {@link ParaText#getNormalString}은 일반 글자만 남기고
     * 탭/줄바꿈 같은 문자 컨트롤을 조용히 버려서, 여러 줄로 나뉜 셀 내용이 한 단어로
     * 붙어버릴 수 있다. hwplib의 공식 텍스트 추출기(ForParagraph)가 문자 코드를 다루는
     * 방식(9=탭, 10=줄바꿈, 24=하이픈)을 그대로 따르고, 표/그림 등 개체를 가리키는
     * 확장 컨트롤 문자는 별도 컨트롤로 처리하므로 여기서는 건너뛴다.
     */
    private String text(Paragraph paragraph) throws IOException {
        ParaText paraText = paragraph.getText();
        if (paraText == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (HWPChar ch : paraText.getCharList()) {
            switch (ch.getType()) {
                case Normal -> sb.append(((HWPCharNormal) ch).getCh());
                case ControlChar, ControlInline -> appendControlChar(sb, ch.getCode());
                default -> {
                    // ControlExtend(표/그림/각주 등)는 addTables()가 컨트롤 자체로 따로 처리한다.
                }
            }
        }
        return sb.toString();
    }

    private void appendControlChar(StringBuilder sb, int code) {
        switch (code) {
            case 9 -> sb.append('\t');
            case 10 -> sb.append('\n');
            case 24 -> sb.append('_'); // hwplib 자체 문서는 "하이픈"이라 적지만, 참조 추출기(ForParagraph)도 "_"로 렌더링한다
            default -> {
                // 그 외 제어 코드(필드 끝, title mark 등)는 표시할 문자가 없어 건너뛴다.
            }
        }
    }
}
