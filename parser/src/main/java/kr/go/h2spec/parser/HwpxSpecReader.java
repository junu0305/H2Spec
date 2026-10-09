package kr.go.h2spec.parser;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VertRelTo;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.shapeobject.ShapePosition;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.common.ObjectType;
import kr.dogfoot.hwpxlib.reader.HWPXReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * HWPX(신형식, OWPML) 파일을 읽어 문단/표를 문서 순서 그대로 {@link Block} 목록으로 변환한다.
 * <p>
 * HWPX는 HWP와 마찬가지로 표를 문단에 딸린 개체로 담는다. 문단의 텍스트를 먼저 확인한 뒤
 * 그 문단에 표 개체가 있으면 이어서 꺼내는 방식으로 순서를 맞춘다.
 */
public class HwpxSpecReader {

    /** 줄 높이 정보가 없는 문단의 첫 줄 높이 (HWPUNIT, 10pt 글자 한 줄) */
    private static final long DEFAULT_LINE_HEIGHT = 1000;

    public List<Block> read(Path hwpx) throws IOException {
        HWPXFile file = readFile(hwpx);

        List<Block> blocks = new ArrayList<>();
        for (SectionXMLFile section : file.sectionXMLFileList().items()) {
            for (Para para : section.paras()) {
                addParagraph(blocks, para);
            }
        }
        return blocks;
    }

    private HWPXFile readFile(Path hwpx) throws IOException {
        try {
            return HWPXReader.fromFile(hwpx.toFile());
        } catch (Exception e) {
            throw new IOException("HWPX 파일을 읽을 수 없습니다: " + hwpx.getFileName(), e);
        }
    }

    /**
     * 문단 하나를 글과 표 블록으로 나눈다. 런 항목 순서가 곧 글자 순서이므로, 제자리에 놓을 표를 만나면
     * 그때까지의 글을 내보내고 표를 꺼낸다. "{표}b) 요청 메시지 명세"처럼 표를 문단 맨 앞에 붙여 제목 위에 그린
     * 문서에서 표가 제목 뒤로 밀리지 않게 한다. 첫 줄 아래로 내려 그린 표는 문단 글 다음에 둔다.
     */
    private void addParagraph(List<Block> blocks, Para para) {
        StringBuilder text = new StringBuilder();
        List<Table> afterText = new ArrayList<>();
        for (int i = 0; i < para.countOfRun(); i++) {
            Run run = para.getRun(i);
            for (int j = 0; j < run.countOfRunItem(); j++) {
                RunItem item = run.getRunItem(j);
                if (item instanceof T t) {
                    appendText(text, t);
                } else if (item instanceof Table table && placedAtAnchor(table, para)) {
                    addHeading(blocks, text.toString());
                    text.setLength(0);
                    blocks.add(new Block.Table(readRows(table)));
                } else if (item instanceof Table table) {
                    afterText.add(table);
                }
            }
        }
        addHeading(blocks, text.toString());
        for (Table table : afterText) {
            blocks.add(new Block.Table(readRows(table)));
        }
    }

    /** 글자처럼 취급하는 표이거나, 문단 기준으로 첫 줄 높이보다 위에 붙인 표는 글자열 속 제자리에 그려진다 */
    private boolean placedAtAnchor(Table table, Para para) {
        ShapePosition pos = table.pos();
        if (pos == null || Boolean.TRUE.equals(pos.treatAsChar())) {
            return true;
        }
        long offset = pos.vertOffset() == null ? 0 : pos.vertOffset();
        return pos.vertRelTo() == VertRelTo.PARA && offset < firstLineHeight(para);
    }

    private long firstLineHeight(Para para) {
        if (para.lineSegArray() == null || para.lineSegArray().count() == 0
                || para.lineSegArray().get(0).vertsize() == null) {
            return DEFAULT_LINE_HEIGHT;
        }
        return Math.max(1, para.lineSegArray().get(0).vertsize());
    }

    private void addHeading(List<Block> blocks, String text) {
        String stripped = SpecLabels.cleanText(text);
        if (!stripped.isEmpty()) {
            blocks.add(new Block.Heading(stripped));
        }
    }

    /**
     * 표를 행/열 격자로 펼친다. 셀은 자신이 놓인 열 주소에 둔다.
     * 가로 병합된 칸은 하나로만 나오고, 세로 병합된 칸은 첫 행에만 나오고 아래 행에서는 빠진다.
     * 나오는 순서대로 이어 붙이면 그 행만 열이 왼쪽으로 밀리므로 가려진 자리는 빈 칸으로 남긴다.
     */
    private List<List<String>> readRows(Table table) {
        List<List<String>> rows = new ArrayList<>();
        int columnCount = table.colCnt() == null ? 0 : table.colCnt();
        for (int r = 0; r < table.countOfTr(); r++) {
            Tr tr = table.getTr(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < tr.countOfTc(); c++) {
                Tc tc = tr.getTc(c);
                int column = colAddr(tc);
                // 열 주소가 앞선 칸과 겹치면 주소를 믿지 않고 이어 붙인다
                while (cells.size() < column) {
                    cells.add("");
                }
                cells.add(cellText(tc));
                for (int i = 1; i < colSpan(tc); i++) {
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

    private int colAddr(Tc tc) {
        if (tc.cellAddr() == null || tc.cellAddr().colAddr() == null) {
            return 0;
        }
        return tc.cellAddr().colAddr();
    }

    private int colSpan(Tc tc) {
        if (tc.cellSpan() == null || tc.cellSpan().colSpan() == null) {
            return 1;
        }
        return Math.max(1, tc.cellSpan().colSpan());
    }

    private String cellText(Tc tc) {
        if (tc.subList() == null) {
            return "";
        }
        // 셀 안의 문단은 줄바꿈으로 잇는다. 그냥 붙이면 "xml/json" + "default : xml"이 한 단어가 된다
        StringBuilder text = new StringBuilder();
        for (Para para : tc.subList().paras()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(paraText(para));
        }
        return SpecLabels.cleanText(text.toString());
    }

    /**
     * 문단의 텍스트를 이어 붙인다. 여러 줄로 나뉜 셀 내용이 한 단어로 붙지 않도록
     * 줄바꿈 개체(LineBreak)를 만나면 개행을 넣는다.
     */
    private String paraText(Para para) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < para.countOfRun(); i++) {
            Run run = para.getRun(i);
            for (int j = 0; j < run.countOfRunItem(); j++) {
                if (run.getRunItem(j) instanceof T t) {
                    appendText(text, t);
                }
            }
        }
        return text.toString();
    }

    /**
     * hwpxlib은 순수 텍스트만 담긴 {@code <hp:t>}를 항목 목록이 아니라 {@link T#onlyText()}
     * 단축 필드에 담는다. 이 경우 {@link T#countOfItems()}가 0이므로 목록만 훑으면 본문이 통째로 빠진다.
     */
    private void appendText(StringBuilder text, T t) {
        if (t.isOnlyText()) {
            text.append(t.onlyText());
            return;
        }
        for (int i = 0; i < t.countOfItems(); i++) {
            TItem item = t.getItem(i);
            if (item instanceof NormalText normal) {
                text.append(normal.text());
            } else if (item._objectType() == ObjectType.hp_lineBreak) {
                text.append('\n');
            } else if (item._objectType() == ObjectType.hp_tab) {
                // HWP 리더가 탭 문자 컨트롤을 탭으로 남기듯 같은 문서의 HWPX판도 같은 글이 되게 한다
                text.append('\t');
            }
        }
    }
}
