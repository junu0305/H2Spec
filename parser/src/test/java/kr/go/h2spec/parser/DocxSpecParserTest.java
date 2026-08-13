package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocxSpecParserTest {

    @TempDir
    Path tempDir;

    @Test
    void 측정소정보_문서에서_오퍼레이션별_IR을_추출한다() throws Exception {
        List<ParsedApi> apis = new DocxSpecParser().parse(fixture());

        // 측정소정보 문서의 상세기능: 측정소 목록 / TM 기준좌표 / 근접측정소 목록
        assertEquals(3, apis.size());

        ParsedApi first = apis.get(0);
        assertEquals("MsrstnList", first.apiId());
        JsonNode api = first.ir().get("api");
        assertEquals("측정소 목록 조회", api.get("apiName").asText());
        assertEquals("http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc", api.get("baseUrl").asText());
        assertEquals("/getMsrstnList", api.get("endpoint").asText());
        assertEquals("GET", api.get("httpMethod").asText());
    }

    @Test
    void 요청_파라미터의_필수여부와_타입을_추론한다() throws Exception {
        ParsedApi first = new DocxSpecParser().parse(fixture()).get(0);
        JsonNode params = first.ir().get("api").get("requestParameters");

        JsonNode serviceKey = findByName(params, "serviceKey");
        assertTrue(serviceKey.get("required").asBoolean());

        JsonNode numOfRows = findByName(params, "numOfRows");
        assertFalse(numOfRows.get("required").asBoolean());
        assertEquals("integer", numOfRows.get("type").asText());

        JsonNode addr = findByName(params, "addr");
        assertEquals("string", addr.get("type").asText());
    }

    @Test
    void 응답_필드를_표준_공공데이터_구조의_경로로_조립한다() throws Exception {
        ParsedApi first = new DocxSpecParser().parse(fixture()).get(0);
        JsonNode fields = first.ir().get("api").get("responseFields");

        assertTrue(hasPath(fields, "response.header.resultCode"));
        assertTrue(hasPath(fields, "response.header.resultMsg"));
        assertTrue(hasPath(fields, "response.body.numOfRows"));
        assertTrue(hasPath(fields, "response.body.items.item[].stationName"));
    }

    @Test
    void 결과코드_필드는_숫자_샘플이어도_string으로_강제한다() throws Exception {
        ParsedApi first = new DocxSpecParser().parse(fixture()).get(0);
        JsonNode fields = first.ir().get("api").get("responseFields");

        // 샘플데이터가 "00"(숫자)이라도 integer로 추론하면 선행 0이 소실된다
        JsonNode resultCode = findByPath(fields, "response.header.resultCode");
        assertEquals("string", resultCode.get("type").asText());
    }

    @Test
    void 산출한_IR이_generator의_검증을_통과한다() throws Exception {
        List<ParsedApi> apis = new DocxSpecParser().parse(fixture());

        for (ParsedApi parsed : apis) {
            Path irFile = tempDir.resolve(parsed.apiId() + ".json");
            Files.writeString(irFile, parsed.ir().toPrettyString());
            IrSpec ir = new IrLoader().load(irFile);
            assertEquals(parsed.apiId(), ir.api().apiId());
        }
    }

    @Test
    void 산출한_IR이_코드_생성까지_관통한다() throws Exception {
        List<ParsedApi> apis = new DocxSpecParser().parse(fixture());

        for (ParsedApi parsed : apis) {
            Path irFile = tempDir.resolve(parsed.apiId() + ".json");
            Files.writeString(irFile, parsed.ir().toPrettyString());
            Path outDir = tempDir.resolve("out-" + parsed.apiId());

            List<Path> written = new CodeGenerator().generate(irFile, outDir);

            assertEquals(3, written.size(), parsed.apiId() + " 산출물 수");
        }
    }

    private Path fixture() throws Exception {
        return Path.of(getClass().getResource("/docs/msrstn-info.docx").toURI());
    }

    private JsonNode findByName(JsonNode params, String name) {
        for (JsonNode param : params) {
            if (name.equals(param.get("name").asText())) {
                return param;
            }
        }
        fail("파라미터 없음: " + name);
        return null;
    }

    private boolean hasPath(JsonNode fields, String path) {
        return findByPath(fields, path) != null;
    }

    private JsonNode findByPath(JsonNode fields, String path) {
        for (JsonNode field : fields) {
            if (path.equals(field.get("path").asText())) {
                return field;
            }
        }
        return null;
    }
}
