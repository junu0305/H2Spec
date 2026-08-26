package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HWPX(신형식) 문서를 읽어 IR까지 만드는지 확인한다. 공공데이터포털이 배포하는 HWPX
 * 기술문서를 구하지 못해, hwpxlib으로 표준 기술문서 구조를 갖춘 파일을 만들어 검증한다.
 */
class HwpxSpecReaderTest {

    @TempDir
    Path tempDir;

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");

    @Test
    void 문단과_표를_문서_순서대로_읽는다() throws Exception {
        Path hwpx = writeSpecFile();

        List<Block> blocks = new HwpxSpecReader().read(hwpx);

        assertEquals("가. 상세기능정보", ((Block.Heading) blocks.get(0)).text());
        assertInstanceOf(Block.Table.class, blocks.get(1));
        assertEquals("나. 요청 메시지 명세", ((Block.Heading) blocks.get(2)).text());
    }

    @Test
    void HWPX_문서에서_오퍼레이션별_IR을_추출한다() throws Exception {
        Path hwpx = writeSpecFile();

        List<ParsedApi> apis = new HwpxSpecParser().parse(hwpx);

        assertEquals(1, apis.size());
        JsonNode api = apis.get(0).ir().get("api");
        assertEquals("TestList", api.get("apiId").asText());
        assertEquals("http://apis.data.go.kr/1234567/TestSvc", api.get("baseUrl").asText());
        assertEquals("/getTestList", api.get("endpoint").asText());
        assertEquals("HWPX", apis.get(0).ir().get("metadata").get("sourceFormat").asText());
    }

    @Test
    void 요청_파라미터와_응답_필드를_읽는다() throws Exception {
        JsonNode api = new HwpxSpecParser().parse(writeSpecFile()).get(0).ir().get("api");

        assertEquals("serviceKey", api.get("requestParameters").get(0).get("name").asText());
        assertEquals("response.header.resultCode",
                api.get("responseFields").get(0).get("path").asText());
        assertEquals("response.body.items.item[].stationName",
                api.get("responseFields").get(1).get("path").asText());
    }

    /** 상세기능정보 / 요청 / 응답 표를 갖춘 최소 HWPX 문서를 만든다 */
    private Path writeSpecFile() throws Exception {
        HWPXFile file = BlankFileMaker.make();
        SectionXMLFile section = file.sectionXMLFileList().get(0);

        heading(section, "가. 상세기능정보");
        table(section, List.of(
                List.of("Call Back URL", "http://apis.data.go.kr/1234567/TestSvc/getTestList")));

        heading(section, "나. 요청 메시지 명세");
        table(section, List.of(HEADER,
                List.of("serviceKey", "서비스키", "100", "1", "-", "발급받은 인증키")));

        heading(section, "다. 응답 메시지 명세");
        table(section, List.of(HEADER,
                List.of("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                List.of("stationName", "측정소명", "20", "1", "종로구", "측정소 이름")));

        Path out = tempDir.resolve("spec.hwpx");
        HWPXWriter.toFilepath(file, out.toString());
        return out;
    }

    private void heading(SectionXMLFile section, String text) {
        Para para = section.addNewPara();
        para.addNewRun().addNewT().addText(text);
    }

    private void table(SectionXMLFile section, List<List<String>> rows) {
        Para para = section.addNewPara();
        Run run = para.addNewRun();
        Table table = run.addNewTable();
        for (List<String> cells : rows) {
            Tr tr = table.addNewTr();
            for (String value : cells) {
                Tc tc = tr.addNewTc();
                tc.createSubList();
                tc.subList().addNewPara().addNewRun().addNewT().addText(value);
            }
        }
    }
}
