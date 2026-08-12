package kr.go.h2spec.parser;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** DOCX 파일을 읽어 문단/표를 문서 순서 그대로 {@link Block} 목록으로 변환한다. */
public class DocxSpecReader {

    public List<Block> read(Path docx) throws IOException {
        try (InputStream is = Files.newInputStream(docx);
             XWPFDocument doc = new XWPFDocument(is)) {
            List<Block> blocks = new ArrayList<>();
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = paragraph.getText().trim();
                    if (!text.isEmpty()) {
                        blocks.add(new Block.Heading(text));
                    }
                } else if (element instanceof XWPFTable table) {
                    blocks.add(new Block.Table(readRows(table)));
                }
            }
            return blocks;
        }
    }

    private List<List<String>> readRows(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText().trim());
            }
            rows.add(cells);
        }
        return rows;
    }
}
