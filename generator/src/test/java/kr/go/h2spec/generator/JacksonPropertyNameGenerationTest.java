package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.TestIr.CLIENT_CLASS;
import static kr.go.h2spec.generator.TestIr.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 두 번째 글자가 대문자인 응답 필드(sGubun, xPos)와 그렇게 바뀌는 이름(Y_POS). Jackson은 getter getSGubun에서 속성 이름을 sgubun으로
 * 지어, 원래 이름을 적지 않으면 응답의 값을 조용히 버린다.
 */
class JacksonPropertyNameGenerationTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void 두_번째_글자가_대문자인_필드도_값을_읽고_한_이름으로_직렬화한다(String format) throws Throwable {
        TestIr ir = new TestIr();
        ir.responseFormat = format;
        ir.fields = new ArrayList<>(List.of(
                field("response.header.resultCode", "string"),
                field("response.body.items.item[].sGubun", "string"),
                field("response.body.items.item[].xPos", "number"),
                field("response.body.items.item[].Y_POS", "number"),
                field("response.body.items.item[].eUserNo", "string"),
                field("response.body.items.item[].stationName", "string"),
                field("response.body.items.item[].rRow[]", "string")));
        String body = "XML".equals(format)
                ? "<response><header><resultCode>00</resultCode></header><body><items><item>"
                  + "<sGubun>A</sGubun><xPos>126.97</xPos><Y_POS>1.5</Y_POS><eUserNo>U1</eUserNo>"
                  + "<stationName>종로구</stationName><rRow>r1</rRow><rRow>r2</rRow></item></items></body></response>"
                : "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":[{"
                  + "\"sGubun\":\"A\",\"xPos\":126.97,\"Y_POS\":1.5,\"eUserNo\":\"U1\",\"stationName\":\"종로구\","
                  + "\"rRow\":[\"r1\",\"r2\"]}]}}}}";

        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve("out"));
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        JsonNode item = new GeneratedClientCall(body).call(classes, CLIENT_CLASS, "KEY", (Object) null)
                .at("/body/items/item/0");

        if ("JSON".equals(format)) {
            assertEquals("A", item.path("sGubun").asText(), item.toString());
            assertEquals(126.97, item.path("xPos").asDouble(), item.toString());
            assertEquals(1.5, item.path("Y_POS").asDouble(), item.toString());
            assertEquals("U1", item.path("eUserNo").asText(), item.toString());
            assertEquals("종로구", item.path("stationName").asText(), item.toString());
            assertEquals("[\"r1\",\"r2\"]", item.path("rRow").toString(), item.toString());
            // getter의 이름(sgubun, xpos)으로 한 번 더 직렬화되지 않는다
            assertEquals(6, item.size(), item.toString());
        } else {
            // 결과 트리는 XML 어노테이션을 모르는 ObjectMapper로 만들어 getter의 이름으로 보인다. 값을 읽었는지만 본다
            assertEquals("A", item.path("sgubun").asText(), item.toString());
            assertEquals(126.97, item.path("xpos").asDouble(), item.toString());
            assertEquals(1.5, item.path("ypos").asDouble(), item.toString());
            assertEquals("U1", item.path("euserNo").asText(), item.toString());
            assertEquals("[\"r1\",\"r2\"]", item.path("rrow").toString(), item.toString());
        }
        String dto = Files.readString(written.get(0));
        assertFalse(dto.contains("\"stationName\""), "Jackson이 같은 이름을 짓는 필드에는 이름을 적지 않는다: " + dto);
        assertTrue(dto.contains("public String getSGubun()"), "getter 이름은 그대로다: " + dto);
    }
}
