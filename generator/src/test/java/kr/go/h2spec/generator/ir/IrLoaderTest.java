package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class IrLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void schemaExample을_역직렬화한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));

        assertEquals("RTMSDataSvcAptTradeDev", ir.api().apiId());
        assertEquals("아파트 매매 실거래 상세 자료 조회", ir.api().apiName());
        assertEquals(5, ir.api().requestParameters().size());
        assertEquals(6, ir.api().responseFields().size());
        assertEquals("00", ir.api().errorSpec().successResultCode());
        assertEquals("kr.go.h2spec.client.landmolit", ir.generatorHints().targetPackage());
    }

    @Test
    void 필수필드_누락시_필드명을_알려준다() throws Exception {
        Path bad = tempDir.resolve("bad.json");
        Files.writeString(bad, """
                {"api": {"apiName": "이름만 있음"}, "generatorHints": {}}
                """);

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(bad));

        assertTrue(e.getMessage().contains("api.apiId"));
        assertTrue(e.getMessage().contains("generatorHints.targetPackage"));
    }

    @Test
    void errorSpec의_successResultCode가_없으면_필드명을_알려준다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api").path("errorSpec")).remove("successResultCode");
        Path file = writeVariant(tree, "missing-success-code.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("api.errorSpec.successResultCode"));
    }

    @Test
    void 엔드포인트가_슬래시로_시작하지_않으면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api")).put("endpoint", "getRTMSDataSvcAptTradeDev");
        Path file = writeVariant(tree, "bad-endpoint-no-slash.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("api.endpoint"));
        assertTrue(e.getMessage().contains("getRTMSDataSvcAptTradeDev"));
    }

    @Test
    void 엔드포인트에_세그먼트가_여러개면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api")).put("endpoint", "/v1/getRTMSDataSvcAptTradeDev");
        Path file = writeVariant(tree, "bad-endpoint-multi-segment.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("api.endpoint"));
        assertTrue(e.getMessage().contains("/v1/getRTMSDataSvcAptTradeDev"));
    }

    @Test
    void httpMethod이_GET이_아니면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api")).put("httpMethod", "POST");
        Path file = writeVariant(tree, "bad-http-method.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("GET"));
        assertTrue(e.getMessage().contains("POST"));
    }

    @Test
    void 파라미터_in이_query가_아니면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ArrayNode params = (ArrayNode) tree.path("api").path("requestParameters");
        for (JsonNode param : params) {
            if ("LAWD_CD".equals(param.path("name").asText())) {
                ((ObjectNode) param).put("in", "path");
            }
        }
        Path file = writeVariant(tree, "bad-param-in.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("in=path"));
        assertTrue(e.getMessage().contains("LAWD_CD"));
    }

    @Test
    void responseFormat이_없으면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api")).remove("responseFormat");
        Path file = writeVariant(tree, "missing-response-format.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("responseFormat"));
    }

    @Test
    void responseFormat이_소문자면_실패한다() throws Exception {
        ObjectNode tree = schemaExampleTree();
        ((ObjectNode) tree.path("api")).put("responseFormat", "xml");
        Path file = writeVariant(tree, "lowercase-response-format.json");

        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> new IrLoader().load(file));

        assertTrue(e.getMessage().contains("responseFormat"));
        assertTrue(e.getMessage().contains("xml"));
    }

    private ObjectNode schemaExampleTree() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return (ObjectNode) mapper.readTree(resource("/ir/schema-example.json").toFile());
    }

    private Path writeVariant(ObjectNode tree, String filename) throws Exception {
        Path file = tempDir.resolve(filename);
        new ObjectMapper().writeValue(file.toFile(), tree);
        return file;
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }
}
