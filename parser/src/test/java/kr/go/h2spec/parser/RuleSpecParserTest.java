package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * rule 엔진의 SpecParser. 기존 파서 3종을 합친 것이라 같은 문서에서 같은 IR과 건너뛴 목록을 내야 한다.
 * 합성 문서는 실제 문서의 구조를 줄여 DOCX로 썼다.
 */
class RuleSpecParserTest {

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");
    private static final String SERVICE = "http://apis.data.go.kr/1234567/TestSvc";

    private final RuleSpecParser parser = new RuleSpecParser();

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {
            "corpus/khoa-realtime-sea-current.hwp",
            "corpus/mohw-emergency-safety-org.hwp",
            "corpus/kdic-account-deposit.hwp",
            "corpus/jejudatahub-wifi-usage.hwp",
            "corpus/nongsaro-eco-farming.hwp",
            "corpus/kosha-certificate-check.hwp",
            "corpus/its-traffic-light.hwp",
            "corpus/jeju-folksong.hwp",
            "corpus/nec-ballot-counting-place.hwpx",
            "msrstn-info.docx",
            "msrstn-info.hwp",
            "nps-disability-review.hwp"})
    void 기존_파서와_같은_IR과_건너뛴_목록을_낸다(String name) throws Exception {
        Path document = resource(name);
        Legacy legacy = legacy(document);

        SpecParseResult result = parser.parse(document);

        assertEquals(withoutParsedAt(legacy.apis()), withoutParsedAt(result.apis()));
        assertEquals(legacy.skipped(), result.skipped());
    }

    @Test
    void 변환한_오퍼레이션이_없으면_기존_파서와_같은_메시지로_FAILED를_던진다() throws Exception {
        Path docx = writeDocx("no-spec.docx",
                List.of(new Block.Heading("개정 이력"), table(row("버전", "변경일"), row("1.0", "2024-01-01"))));

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser.parse(docx));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("문서에서 상세기능 명세를 찾지 못했습니다: no-spec.docx", e.getMessage());
        assertEquals(assertThrows(IllegalArgumentException.class, () -> new DocxSpecParser().parse(docx)).getMessage(),
                e.getMessage());
    }

    @Test
    void 건너뛴_오퍼레이션만_있으면_그_이유를_메시지에_붙인다() throws Exception {
        Path docx = writeDocx("skipped-only.docx", requestOnly(row("상세기능명(국문)", "목록 조회"),
                row("오퍼레이션명(영문)", "getTestList"), row("Call Back URL", SERVICE + "/getTestList")));

        SpecParseException e = assertThrows(SpecParseException.class, () -> parser.parse(docx));

        assertEquals(SpecParseException.Kind.FAILED, e.kind());
        assertEquals("문서에서 상세기능 명세를 찾지 못했습니다: skipped-only.docx (getTestList: 응답 필드 표를 찾지 못했습니다)",
                e.getMessage());
        // 기존 파서는 같은 메시지의 IllegalArgumentException을 던지고, 실패한 뒤에도 skipped()로 이유를 알린다
        DocxSpecParser legacy = new DocxSpecParser();
        assertEquals(e.getMessage(), assertThrows(IllegalArgumentException.class, () -> legacy.parse(docx)).getMessage());
        assertEquals(List.of("getTestList: 응답 필드 표를 찾지 못했습니다"), legacy.skipped());
    }

    /** 기존 파서 3종 가운데 확장자에 맞는 것으로 읽은 결과 */
    private Legacy legacy(Path document) throws IOException {
        String name = document.getFileName().toString();
        if (name.endsWith(".docx")) {
            DocxSpecParser docx = new DocxSpecParser();
            return new Legacy(docx.parse(document), docx.skipped());
        }
        if (name.endsWith(".hwpx")) {
            HwpxSpecParser hwpx = new HwpxSpecParser();
            return new Legacy(hwpx.parse(document), hwpx.skipped());
        }
        HwpSpecParser hwp = new HwpSpecParser();
        return new Legacy(hwp.parse(document), hwp.skipped());
    }

    private record Legacy(List<ParsedApi> apis, List<String> skipped) {
    }

    /** 변환 시각은 실행마다 달라 지우고 비교한다 */
    private List<ParsedApi> withoutParsedAt(List<ParsedApi> apis) {
        List<ParsedApi> stripped = new ArrayList<>();
        for (ParsedApi api : apis) {
            ObjectNode ir = api.ir().deepCopy();
            ir.withObject("/metadata").remove("parsedAt");
            stripped.add(new ParsedApi(api.apiId(), ir));
        }
        return stripped;
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource("/docs/" + name).toURI());
    }

    /** 블록을 문서 순서대로 DOCX의 문단과 표로 쓴다 */
    @SafeVarargs
    private Path writeDocx(String fileName, List<Block>... parts) throws IOException {
        Path out = tempDir.resolve(fileName);
        try (XWPFDocument doc = new XWPFDocument()) {
            for (List<Block> part : parts) {
                for (Block block : part) {
                    if (block instanceof Block.Heading heading) {
                        doc.createParagraph().createRun().setText(heading.text());
                    } else if (block instanceof Block.Table table) {
                        writeTable(doc, table.rows());
                    }
                }
            }
            try (OutputStream os = Files.newOutputStream(out)) {
                doc.write(os);
            }
        }
        return out;
    }

    private void writeTable(XWPFDocument doc, List<List<String>> rows) {
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        XWPFTable table = doc.createTable(rows.size(), columns);
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                table.getRow(r).getCell(c).setText(rows.get(r).get(c));
            }
        }
    }

    /** 응답 표가 없는 오퍼레이션 하나 */
    @SafeVarargs
    private List<Block> requestOnly(List<String>... infoRows) {
        return List.of(
                new Block.Heading("상세기능정보"),
                table(infoRows),
                new Block.Heading("요청 메시지 명세"),
                table(HEADER, row("pageNo", "페이지 번호", "4", "0", "1", "페이지 번호")));
    }

    @SafeVarargs
    private Block.Table table(List<String>... rows) {
        return new Block.Table(List.of(rows));
    }

    private List<String> row(String... cells) {
        return List.of(cells);
    }
}
