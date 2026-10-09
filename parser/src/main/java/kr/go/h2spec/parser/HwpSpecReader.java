package kr.go.h2spec.parser;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.gso.VertRelTo;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType;
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
 * 표는 화면에 놓인 자리를 따른다. 글자처럼 취급하는 표와, 문단 위쪽(첫 줄 높이 안)에 붙여 둔 표는
 * 글자열 속 자리에서 꺼낸다. "{표}b) 요청 메시지 명세"처럼 정보 표를 다음 제목 문단 맨 앞에 붙여 제목 위에
 * 그린 문서가 있어, 글을 먼저 내보내면 표가 다음 구간 제목 뒤로 밀린다. 그 밖의 표는 문단 글 뒤에 둔다.
 */
public class HwpSpecReader {

    /** 줄 높이 정보가 없는 문단의 첫 줄 높이 (HWPUNIT, 10pt 글자 한 줄) */
    private static final long DEFAULT_LINE_HEIGHT = 1000;

    public List<Block> read(Path hwp) throws IOException {
        HWPFile hwpFile = readFile(hwp);

        List<Block> blocks = new ArrayList<>();
        for (Section section : hwpFile.getBodyText().getSectionList()) {
            for (Paragraph paragraph : section) {
                addParagraph(blocks, paragraph);
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

    /**
     * 문단 하나를 글과 표 블록으로 나눈다. 확장 컨트롤 문자(표·그림·구역 정의 등)는 문단의 컨트롤 목록과
     * 같은 순서로 하나씩 짝을 이루므로, 글자열을 따라가며 제자리에 놓을 표를 만나면 그때까지의 글을 끊는다.
     * 짝이 맞지 않는 문단은 글 다음에 표를 두는 방식으로 되돌아간다.
     */
    private void addParagraph(List<Block> blocks, Paragraph paragraph) throws IOException {
        List<Control> controls = paragraph.getControlList() == null ? List.of() : paragraph.getControlList();
        ParaText paraText = paragraph.getText();
        if (paraText == null || extendedCharCount(paraText) != controls.size()) {
            addHeading(blocks, text(paragraph));
            for (Control control : controls) {
                addTable(blocks, control);
            }
            return;
        }
        StringBuilder text = new StringBuilder();
        List<Control> afterText = new ArrayList<>();
        int controlIndex = 0;
        for (HWPChar ch : paraText.getCharList()) {
            switch (ch.getType()) {
                case Normal -> text.append(((HWPCharNormal) ch).getCh());
                case ControlChar, ControlInline -> appendControlChar(text, ch.getCode());
                case ControlExtend -> {
                    Control control = controls.get(controlIndex++);
                    if (control instanceof ControlTable table && placedAtAnchor(table, paragraph)) {
                        addHeading(blocks, text.toString());
                        text.setLength(0);
                        addTable(blocks, control);
                    } else {
                        afterText.add(control);
                    }
                }
            }
        }
        addHeading(blocks, text.toString());
        for (Control control : afterText) {
            addTable(blocks, control);
        }
    }

    /**
     * 표가 글자열 속 제자리에 그려지는지 본다. 글자처럼 취급하는 표이거나, 문단 기준으로 첫 줄 높이보다 위에
     * 붙인 표다. 문단 기준이라도 첫 줄 아래로 내려 그린 표(세로 오프셋이 큰 표)는 문단 글 다음에 보인다.
     */
    private boolean placedAtAnchor(ControlTable table, Paragraph paragraph) {
        CtrlHeaderGso header = table.getHeader();
        if (header == null) {
            return false;
        }
        if (header.getProperty().isLikeWord()) {
            return true;
        }
        return header.getProperty().getVertRelTo() == VertRelTo.Para && header.getyOffset() < firstLineHeight(paragraph);
    }

    private long firstLineHeight(Paragraph paragraph) {
        if (paragraph.getLineSeg() == null || paragraph.getLineSeg().getLineSegItemList().isEmpty()) {
            return DEFAULT_LINE_HEIGHT;
        }
        return Math.max(1, paragraph.getLineSeg().getLineSegItemList().get(0).getLineHeight());
    }

    private long extendedCharCount(ParaText paraText) {
        return paraText.getCharList().stream().filter(ch -> ch.getType() == HWPCharType.ControlExtend).count();
    }

    private void addHeading(List<Block> blocks, String text) {
        String stripped = SpecLabels.cleanText(text);
        if (!stripped.isEmpty()) {
            blocks.add(new Block.Heading(stripped));
        }
    }

    /**
     * control.getType()이 아니라 instanceof로 판별한다. hwplib의 ControlType.ctrlIdOf()는
     * 알 수 없는 컨트롤 id를 조용히 Table로 잘못 분류하고, FactoryForControl.create()는
     * 알 수 없는 컨트롤 id에 대해 null을 넣기도 한다 — getType() 비교 후 캐스팅하면
     * ClassCastException/NPE로 이어질 수 있다. DOCX 리더(instanceof XWPFTable)와도 같은 방식이다.
     */
    private void addTable(List<Block> blocks, Control control) throws IOException {
        if (control instanceof ControlTable table) {
            blocks.add(new Block.Table(readRows(table)));
        }
    }

    /**
     * 표를 행/열 격자로 펼친다. 셀은 자신이 놓인 열 주소에 둔다.
     * 가로 병합된 칸은 하나로만 나오고, 세로 병합된 칸은 첫 행에만 나오고 아래 행에서는 빠진다.
     * 나오는 순서대로 이어 붙이면 그 행만 열이 왼쪽으로 밀려, 들여쓴 자식 행의 국문명이
     * 항목크기 자리로 가는 식으로 값이 어긋난다. 병합으로 가려진 자리는 빈 칸으로 남긴다.
     */
    private List<List<String>> readRows(ControlTable table) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        int columnCount = table.getTable() == null ? 0 : table.getTable().getColumnCount();
        for (Row row : table.getRowList()) {
            List<String> cells = new ArrayList<>();
            for (Cell cell : row.getCellList()) {
                int column = colIndex(cell);
                // 열 주소가 앞선 칸과 겹치면 주소를 믿지 않고 이어 붙인다
                while (cells.size() < column) {
                    cells.add("");
                }
                cells.add(cellText(cell));
                for (int i = 1; i < colSpan(cell); i++) {
                    cells.add("");
                }
            }
            // 끝 열이 위 행에서 세로 병합돼 가려진 행도 다른 행과 같은 폭으로 맞춘다
            while (cells.size() < columnCount) {
                cells.add("");
            }
            rows.add(cells);
        }
        return rows;
    }

    private int colIndex(Cell cell) {
        return cell.getListHeader() == null ? 0 : cell.getListHeader().getColIndex();
    }

    private int colSpan(Cell cell) {
        return cell.getListHeader() == null ? 1 : Math.max(1, cell.getListHeader().getColSpan());
    }

    /**
     * 셀 안의 문단을 줄바꿈으로 잇는다. 구분 없이 붙이면 "xml/json" + "default : xml"이 "xml/jsondefault : xml"로,
     * "response" + "http://…"가 "responsehttp://…"로 붙는다.
     */
    private String cellText(Cell cell) throws IOException {
        StringBuilder text = new StringBuilder();
        for (Paragraph paragraph : cell.getParagraphList()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(text(paragraph));
        }
        return SpecLabels.cleanText(text.toString());
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
                    // ControlExtend(표/그림/각주 등)는 addParagraph()가 컨트롤 자체로 따로 처리한다.
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
