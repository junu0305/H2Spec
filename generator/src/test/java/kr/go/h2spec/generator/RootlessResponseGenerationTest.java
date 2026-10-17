package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.generator.ir.ApiAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.TestIr.CLIENT_CLASS;
import static kr.go.h2spec.generator.TestIr.field;
import static kr.go.h2spec.generator.TestIr.path;
import static kr.go.h2spec.generator.TestIr.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 루트 하나로 감싸이지 않고 맨 바깥에 필드가 바로 오는 JSON 응답(api.responseRootless). 생성 DTO가 문서의 응답 예제를
 * 실제로 읽는지 컴파일한 클라이언트를 불러 본다.
 */
class RootlessResponseGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void 맨_바깥에_header와_body가_오는_JSON_응답을_읽는다() throws Throwable {
        // 안전보건공단 발급문서 진위확인(kosha)의 응답 꼴. 루트를 풀면 "Root name ('header') does not match expected"로 실패한다
        TestIr ir = kosha();

        Path classes = generateAndCompile(ir);
        JsonNode response = new GeneratedClientCall("""
                {"header": {"resultCode": "00", "resultMsg": "NORMAL SERVICE."},
                 "body": {"totalCount": 1, "items": {"item": [{"docAuthYn": "Y"}]}}}""")
                .call(classes, CLIENT_CLASS, "key", (Object) null);

        assertEquals("00", response.at("/header/resultCode").asText());
        assertEquals(1, response.at("/body/totalCount").asLong());
        assertEquals("Y", response.at("/body/items/item/0/docAuthYn").asText());
        assertFalse(dto().contains("JsonRootName"), dto());
        assertFalse(client().contains("UNWRAP_ROOT_VALUE"), client());
    }

    @Test
    void 제주_와이파이처럼_경로의_키와_루트_없는_응답을_함께_쓰는_IR로_문서의_응답_예제를_읽는다() throws Throwable {
        // jejudatahub-wifi-usage.hwp: 주소 끝의 {your_appkey}로 키를 받고, 맨 바깥에 totCnt·hasMore·data가 온다
        TestIr ir = new TestIr();
        ir.baseUrl = "https://open.jejudatahub.net/api/proxy";
        ir.endpoint = "/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}";
        ir.responseFormat = "JSON";
        ir.params = new ArrayList<>(List.of(path("your_appkey"), query("number", false), query("searchDate", true)));
        ir.auth = new ApiAuth(ApiAuth.PATH, "your_appkey");
        ir.rootless = true;
        ir.fields = new ArrayList<>(List.of(field("totCnt", "integer"), field("hasMore", "boolean"),
                field("data[].baseDate", "string"), field("data[].uploadBytes", "integer"), field("data[].emd", "string")));

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall("""
                {
                  "totCnt": 13,
                  "hasMore": true,
                  "data": [
                    {"baseDate": "20190704", "uploadBytes": "46645463", "downloadBytes": "1163358166", "emd": "이호1동"},
                    {"baseDate": "20190704", "uploadBytes": "24387181", "downloadBytes": "253939224", "emd": "이호1동"}
                  ]
                }""");
        JsonNode response = call.call(classes, CLIENT_CLASS, "app-key", null, "20190704");

        assertEquals(ir.baseUrl + "/7tttaDaD5ataaba75a57ttDtaDb6tb7t/app-key?searchDate=20190704", call.uri().toString());
        assertEquals(13, response.get("totCnt").asLong());
        assertTrue(response.get("hasMore").asBoolean());
        assertEquals(2, response.get("data").size());
        assertEquals(46645463, response.at("/data/0/uploadBytes").asLong(), "예제에서 문자열인 숫자도 읽는다");
        assertEquals("이호1동", response.at("/data/1/emd").asText());
    }

    @Test
    void 루트_없는_응답의_OpenAPI_스키마는_맨_바깥_필드를_바로_적고_루트로_감싼_JSON은_루트_키_아래에_적는다() throws Exception {
        TestIr wrapped = new TestIr();
        wrapped.responseFormat = "JSON";

        JsonNode rootless = schema(kosha(), "rootless");
        JsonNode rooted = schema(wrapped, "rooted");
        JsonNode xml = schema(new TestIr(), "xml");

        assertEquals(List.of("header", "body"), names(rootless.get("properties")));
        assertEquals(List.of("response"), names(rooted.get("properties")), "JSON 바디는 {\"response\": {…}}다");
        assertEquals(List.of("header", "body"), names(rooted.at("/properties/response/properties")));
        assertEquals(List.of("header", "body"), names(xml.get("properties")), "XML은 루트 요소가 문서 자체다");
    }

    @Test
    void responseRootless는_XML_응답에_쓸_수_없고_아무_파일도_쓰지_않는다() throws Exception {
        // CLI의 --format xml이 루트 없는 JSON IR을 XML로 바꾼 경우도 같다
        TestIr ir = kosha();
        ir.responseFormat = "XML";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), tempDir.resolve("out")));

        assertTrue(e.getMessage().contains("api.responseRootless는 JSON 응답에만 쓸 수 있습니다"), e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 루트로_감싼_응답인데_첫_칸이_여럿이거나_리스트이거나_값이면_responseRootless를_쓰라고_알린다() {
        // 그대로 만들면 컴파일은 되지만 응답을 읽지 못하는 DTO가 나온다
        List<List<String>> cases = List.of(
                List.of("header.resultCode", "body.totalCount"),
                List.of("data[].baseDate"),
                List.of("totCnt"));

        for (List<String> paths : cases) {
            TestIr ir = new TestIr();
            ir.responseFormat = "JSON";
            ir.fields = new ArrayList<>(paths.stream().map(p -> field(p, "string")).toList());

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new CodeGenerator().generate(ir.build(), tempDir.resolve("out")));
            assertTrue(e.getMessage().contains("api.responseRootless를 true로"), e.getMessage());
        }
    }

    @Test
    void 루트_없는_응답의_필드_이름_오류는_맨_바깥부터의_경로로_알린다() {
        TestIr ir = kosha();
        ir.fields.add(field("body.station_name", "string"));
        ir.fields.add(field("body.stationName", "string"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), tempDir.resolve("out")));

        assertTrue(e.getMessage().contains("(body.station_name, body.stationName)"), e.getMessage());
    }

    /** 안전보건공단 발급문서 진위확인처럼 맨 바깥에 header·body가 오는 JSON 응답 */
    private static TestIr kosha() {
        TestIr ir = new TestIr();
        ir.responseFormat = "JSON";
        ir.rootless = true;
        ir.fields = new ArrayList<>(List.of(field("header.resultCode", "string"), field("header.resultMsg", "string"),
                field("body.totalCount", "integer"), field("body.items.item[].docAuthYn", "string")));
        return ir;
    }

    private Path generateAndCompile(TestIr ir) throws IOException {
        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve("out"));
        Path classes = tempDir.resolve("classes");
        assertCompiles(written, classes);
        return classes;
    }

    private String client() throws IOException {
        return Files.readString(tempDir.resolve("out/kr/go/h2spec/client/sampleapi/SampleApiClient.java"));
    }

    private String dto() throws IOException {
        return Files.readString(tempDir.resolve("out/kr/go/h2spec/client/sampleapi/dto/SampleApiResponse.java"));
    }

    /** 정상 응답 바디의 스키마 */
    private JsonNode schema(TestIr ir, String name) throws IOException {
        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve(name));
        JsonNode content = new ObjectMapper().readTree(written.get(2).toFile())
                .at("/paths/~1getSample/get/responses/200/content");
        return content.elements().next().get("schema");
    }

    private static List<String> names(JsonNode object) {
        List<String> names = new ArrayList<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).map(dir::relativize).toList();
        }
    }
}
