package kr.go.h2spec.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.TestIr.CLIENT_CLASS;
import static kr.go.h2spec.generator.TestIr.field;
import static kr.go.h2spec.generator.TestIr.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 식별자에 못 쓰는 글자가 든 이름. Java 이름은 그 글자를 구분자로 보고 잇고, 요청과 응답에는 원래 이름을 쓴다 */
class ParameterNameGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void 대괄호_콜론_점이_든_요청_파라미터는_Java_이름을_고쳐_인자로_받고_질의에는_원래_이름을_쓴다() throws Throwable {
        // 한국교육학술정보원 해외대학 한글명 API의 검색 조건 cond[country_nm::EQ]
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("serviceKey", true), query("cond[country_nm::EQ]", false),
                query("item.name", false)));

        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve("out"));
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        GeneratedClientCall call = new GeneratedClientCall("<response><header><resultCode>00</resultCode></header>"
                + "<body><items><item><stationName>종로구</stationName></item></items></body></response>");
        call.call(classes, CLIENT_CLASS, "KEY", "미국", "a b");

        assertEquals(ir.baseUrl + "/getSample?serviceKey=KEY&cond[country_nm::EQ]=%EB%AF%B8%EA%B5%AD&item.name=a+b",
                call.uri().toString());
        String client = Files.readString(written.get(1));
        assertTrue(client.contains("String condCountryNmEq, String itemName"), client);
    }

    @Test
    void 식별자에_못_쓰는_글자가_든_응답_필드는_원래_이름으로_읽는다() throws Throwable {
        TestIr ir = new TestIr();
        ir.responseFormat = "JSON";
        ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                field("response.body.items.item[].addr(road)", "string")));

        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve("out"));
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        var response = new GeneratedClientCall("{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"items\":{\"item\":[{\"addr(road)\":\"세종대로 110\"}]}}}}")
                .call(classes, CLIENT_CLASS, "KEY", (Object) null);

        assertEquals("세종대로 110", response.at("/body/items/item/0/addr(road)").asText());
        assertTrue(Files.readString(written.get(0)).contains("private String addrRoad;"));
    }

    @Test
    void 예약어_이름의_응답_필드와_요청_파라미터도_생성하고_원래_이름으로_주고받는다() throws Throwable {
        // 한국과학기술기획평가원 보고서 상세: 응답에 abstract가 있다
        TestIr ir = new TestIr();
        ir.responseFormat = "JSON";
        ir.params = new ArrayList<>(List.of(query("serviceKey", true), query("default", false)));
        ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                field("response.body.item.abstract", "string"), field("response.body.item.1stAuthor", "string")));

        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve("out"));
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        GeneratedClientCall call = new GeneratedClientCall("{\"response\":{\"header\":{\"resultCode\":\"00\"},"
                + "\"body\":{\"item\":{\"abstract\":\"요약\",\"1stAuthor\":\"홍\"}}}}");
        var response = call.call(classes, CLIENT_CLASS, "KEY", "Y");

        assertEquals(ir.baseUrl + "/getSample?serviceKey=KEY&default=Y", call.uri().toString());
        assertEquals("요약", response.at("/body/item/abstract").asText(), response.toString());
        assertEquals("홍", response.at("/body/item/1stAuthor").asText(), response.toString());
        assertTrue(Files.readString(written.get(0)).contains("private String abstract_;"));
    }
}
