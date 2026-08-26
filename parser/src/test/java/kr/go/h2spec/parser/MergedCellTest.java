package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 표에 가로 병합 셀이 있으면 그 행만 칸 수가 줄어 열 위치가 밀린다.
 * 리더가 병합 폭만큼 빈 칸을 채우고, 머리행이 알려주는 인덱스로 읽어야 값이 맞는다.
 */
class MergedCellTest {

    @TempDir
    Path tempDir;

    @Test
    void 가로_병합된_행도_열_위치를_유지한다() throws Exception {
        Path docx = tempDir.resolve("merged.docx");
        writeSpecDocx(docx);

        List<Block> blocks = new DocxSpecReader().read(docx);
        // 마지막 표가 병합 셀이 있는 응답 메시지 명세 표다
        Block.Table table = blocks.stream()
                .filter(b -> b instanceof Block.Table).map(Block.Table.class::cast)
                .reduce((first, second) -> second).orElseThrow();

        assertEquals(6, table.rows().get(0).size(), "머리행은 6칸");
        assertEquals(6, table.rows().get(1).size(),
                "앞 두 칸이 병합된 행도 머리행과 같은 6칸이어야 한다: " + table.rows().get(1));
    }

    @Test
    void 병합된_행의_필드도_응답에_담는다() throws Exception {
        Path docx = tempDir.resolve("merged.docx");
        writeSpecDocx(docx);

        List<ParsedApi> apis = new DocxSpecParser().parse(docx);
        JsonNode fields = apis.get(0).ir().get("api").get("responseFields");

        assertEquals(1, fields.size(), fields.toString());
        assertEquals("response.body.items.item[].mergedName", fields.get(0).get("path").asText());
        assertEquals("설명", fields.get(0).get("description").asText());
    }

    /** 상세기능정보 / 요청 / 응답 표를 갖춘 최소 문서. 응답 표의 데이터 행만 앞 두 칸을 병합한다. */
    private void writeSpecDocx(Path out) throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("가. 상세기능정보");
            XWPFTable info = doc.createTable(1, 2);
            info.getRow(0).getCell(0).setText("Call Back URL");
            info.getRow(0).getCell(1).setText("http://apis.data.go.kr/1234567/TestSvc/getTestList");

            doc.createParagraph().createRun().setText("나. 요청 메시지 명세");
            specTable(doc, "serviceKey", "인증키", false);

            doc.createParagraph().createRun().setText("다. 응답 메시지 명세");
            specTable(doc, "mergedName", "병합항목", true);

            try (OutputStream os = Files.newOutputStream(out)) {
                doc.write(os);
            }
        }
    }

    private void specTable(XWPFDocument doc, String name, String korName, boolean mergeFirstTwo) {
        XWPFTable table = doc.createTable(2, 6);
        String[] header = {"항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명"};
        for (int i = 0; i < header.length; i++) {
            table.getRow(0).getCell(i).setText(header[i]);
        }
        XWPFTableRow row = table.getRow(1);
        if (mergeFirstTwo) {
            row.getCell(0).setText(name);
            row.getCell(0).getCTTc().addNewTcPr().addNewGridSpan().setVal(BigInteger.valueOf(2));
            row.getTableCells().remove(1);
            row.getCtRow().removeTc(1);
            String[] rest = {"20", "1", "샘플", "설명"};
            for (int i = 0; i < rest.length; i++) {
                row.getCell(i + 1).setText(rest[i]);
            }
        } else {
            String[] values = {name, korName, "20", "1", "샘플", "설명"};
            for (int i = 0; i < values.length; i++) {
                row.getCell(i).setText(values[i]);
            }
        }
    }
}
