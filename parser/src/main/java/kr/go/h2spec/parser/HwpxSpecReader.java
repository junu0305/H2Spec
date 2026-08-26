package kr.go.h2spec.parser;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
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

    public List<Block> read(Path hwpx) throws IOException {
        HWPXFile file = readFile(hwpx);

        List<Block> blocks = new ArrayList<>();
        for (SectionXMLFile section : file.sectionXMLFileList().items()) {
            for (Para para : section.paras()) {
                addHeading(blocks, para);
                addTables(blocks, para);
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

    private void addHeading(List<Block> blocks, Para para) {
        String text = paraText(para).trim();
        if (!text.isEmpty()) {
            blocks.add(new Block.Heading(text));
        }
    }

    private void addTables(List<Block> blocks, Para para) {
        for (int i = 0; i < para.countOfRun(); i++) {
            Run run = para.getRun(i);
            for (int j = 0; j < run.countOfRunItem(); j++) {
                RunItem item = run.getRunItem(j);
                if (item instanceof Table table) {
                    blocks.add(new Block.Table(readRows(table)));
                }
            }
        }
    }

    private List<List<String>> readRows(Table table) {
        List<List<String>> rows = new ArrayList<>();
        for (int r = 0; r < table.countOfTr(); r++) {
            Tr tr = table.getTr(r);
            List<String> cells = new ArrayList<>();
            for (int c = 0; c < tr.countOfTc(); c++) {
                Tc tc = tr.getTc(c);
                cells.add(cellText(tc));
                // 가로 병합된 칸은 하나로만 나오므로 나머지 폭을 빈 칸으로 채워
                // 다른 행과 열 위치를 맞춘다. 채우지 않으면 그 행만 열이 밀린다.
                for (int i = 1; i < colSpan(tc); i++) {
                    cells.add("");
                }
            }
            rows.add(cells);
        }
        return rows;
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
        StringBuilder text = new StringBuilder();
        for (Para para : tc.subList().paras()) {
            text.append(paraText(para));
        }
        return text.toString().trim();
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
            }
        }
    }
}
