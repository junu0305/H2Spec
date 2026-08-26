package kr.go.h2spec.parser;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 커밋된 HWPX 픽스처를 다시 만드는 생성기. 포털이 HWPX 기술문서를 배포하지 않아
 * 표준 기술문서 구조를 합성한다. {@code ./gradlew :parser:writeHwpxFixture} 로만 실행되고
 * 일반 테스트 실행에서는 제외된다(parser/build.gradle).
 */
class HwpxFixtureWriter {
    private static final List<String> H =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");

    @Test
    void write() throws Exception {
        HWPXFile file = BlankFileMaker.make();
        SectionXMLFile s = file.sectionXMLFileList().get(0);
        heading(s, "가. 상세기능정보");
        table(s, List.of(List.of("오퍼레이션명(영문)", "getMsrstnList"),
                List.of("Call Back URL", "http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList")));
        heading(s, "나. 요청 메시지 명세");
        table(s, List.of(H,
                List.of("serviceKey", "인증키", "100", "1", "-", "공공데이터포털에서 받은 인증키"),
                List.of("numOfRows", "한 페이지 결과 수", "4", "0", "10", "한 페이지 결과 수"),
                List.of("addr", "주소", "20", "0", "서울", "주소")));
        heading(s, "다. 응답 메시지 명세");
        table(s, List.of(H,
                List.of("resultCode", "결과코드", "2", "1", "00", "결과코드"),
                List.of("resultMsg", "결과메시지", "50", "1", "NORMAL_CODE", "결과메시지"),
                List.of("stationName", "측정소명", "20", "1", "종로구", "측정소 이름"),
                List.of("addr", "측정소 주소", "100", "1", "서울 종로구 종로35가길 19", "측정소가 위치한 주소"),
                List.of("totalCount", "전체 결과 수", "4", "1", "642", "전체 결과 수")));
        String out = System.getProperty("h2spec.fixture.out");
        assertNotNull(out, "h2spec.fixture.out 시스템 속성이 필요합니다. ./gradlew :parser:writeHwpxFixture 로 실행하세요");
        HWPXWriter.toFilepath(file, out);
    }

    private void heading(SectionXMLFile s, String text) {
        s.addNewPara().addNewRun().addNewT().addText(text);
    }

    private void table(SectionXMLFile s, List<List<String>> rows) {
        Para para = s.addNewPara();
        Table table = para.addNewRun().addNewTable();
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
