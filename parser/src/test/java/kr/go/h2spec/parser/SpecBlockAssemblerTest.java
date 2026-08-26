package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 천문연구원 특일 정보제공 서비스 문서처럼 표준 기술문서와 구조가 다른 문서를 다룬다.
 * Call Back URL이 N/A이고, 정보 표를 헤딩 대신 첫 셀로 알아봐야 하며,
 * 응답 표의 자식 행이 빈 셀로 들여쓰기되어 있다.
 */
class SpecBlockAssemblerTest {

    private static final List<String> HEADER =
            List.of("항목명(영문)", "항목명(국문)", "항목크기", "항목구분", "샘플데이터", "항목설명");

    @Test
    void 서비스_URL_표와_오퍼레이션명으로_엔드포인트를_조립한다() {
        // Call Back URL이 N/A라 서비스 URL 표의 운영환경 주소로 baseUrl을 잡는다
        List<ParsedApi> apis = parse(astronomyBlocks());

        assertEquals(1, apis.size());
        JsonNode api = apis.get(0).ir().get("api");
        assertEquals("http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService",
                api.get("baseUrl").asText());
        assertEquals("/getHoliDeInfo", api.get("endpoint").asText());
        assertEquals("HoliDeInfo", api.get("apiId").asText());
    }

    @Test
    void 들여쓴_자식_행도_응답_필드로_읽는다() {
        // "Item | 0..n" 컨테이너 아래 자식 행은 앞 칸이 비어 한 칸씩 밀려 있다
        List<ParsedApi> apis = parse(astronomyBlocks());
        JsonNode fields = apis.get(0).ir().get("api").get("responseFields");

        assertTrue(hasPath(fields, "response.body.items.item[].locdate"), fields.toString());
        assertTrue(hasPath(fields, "response.body.items.item[].dateName"), fields.toString());
        assertTrue(hasPath(fields, "response.header.resultCode"));
    }

    private List<ParsedApi> parse(List<Block> blocks) {
        return new SpecBlockAssembler().parse(blocks, "특일정보.docx", "DOCX");
    }

    private boolean hasPath(JsonNode fields, String path) {
        for (JsonNode f : fields) {
            if (path.equals(f.get("path").asText())) {
                return true;
            }
        }
        return false;
    }

    /** 천문연구원 문서의 구조를 최소로 재현한 블록 목록 */
    private List<Block> astronomyBlocks() {
        return List.of(
                new Block.Heading("가. 서비스 개요"),
                new Block.Table(List.of(
                        List.of("서비스 정보", "서비스명(영문)", "SpcdeInfoService"),
                        List.of("서비스 URL", "개발환경", "http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService"),
                        List.of("", "운영환경", "http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService"))),
                new Block.Heading("국경일 및 공휴일 정보 조회 명세"),
                new Block.Table(List.of(
                        List.of("오퍼레이션 정보", "오퍼레이션 번호", "1", "오퍼레이션명(국문)", "국경일 정보조회"),
                        List.of("", "오퍼레이션명(영문)", "getHoliDeInfo"),
                        List.of("", "Call Back URL", "N/A"))),
                new Block.Heading("요청 메시지 명세"),
                new Block.Table(List.of(
                        HEADER,
                        List.of("solYear", "연", "4", "1", "2019", "연"),
                        List.of("ServiceKey", "서비스키", "", "1", "", "발급받은 서비스키"))),
                new Block.Heading("응답 메시지 명세"),
                new Block.Table(List.of(
                        HEADER,
                        List.of("resultCode", "결과코드", "", "1", "00", "00:성공"),
                        List.of("Item", "", "", "0..n", "", ""),
                        List.of("", "locdate", "날짜", "8", "1", "20190301", "날짜"),
                        List.of("", "dateName", "명칭", "50", "1", "삼일절", "명칭"))));
    }
}
