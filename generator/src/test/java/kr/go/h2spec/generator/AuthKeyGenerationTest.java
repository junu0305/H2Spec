package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.client.interceptor.PublicDataApiException;
import kr.go.h2spec.generator.emit.JavaNames;
import kr.go.h2spec.generator.ir.ApiAuth;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.TestIr.CLIENT_CLASS;
import static kr.go.h2spec.generator.TestIr.path;
import static kr.go.h2spec.generator.TestIr.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * api.auth로 정한 자리에 인증키를 넣는 생성 코드. 컴파일한 클라이언트를 불러 요청에 키가 들어간 자리와,
 * 오류 응답·파싱 실패의 예외에서 키 값이 가려지는지 본다.
 */
class AuthKeyGenerationTest {

    /** 공공데이터포털 키처럼 인코딩하면 꼴이 바뀌는 글자(+ / =)와, 질의(+)와 경로(%20)에서 다르게 쓰는 공백을 넣는다 */
    private static final String KEY = "K3y+/= x";
    private static final String QUERY_KEY = "K3y%2B%2F%3D+x";
    private static final String PATH_KEY = "K3y%2B%2F%3D%20x";
    private static final String OK = "<response><header><resultCode>00</resultCode></header><body><items><item>"
            + "<stationName>종로구</stationName></item></items></body></response>";

    @TempDir
    Path tempDir;

    @Test
    void 이름이_다른_쿼리_키는_그_이름으로_보내고_메서드_인자에서_뺀다() throws Throwable {
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("apiKey", true), query("pageNo", false)));
        ir.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        JsonNode response = call.call(classes, CLIENT_CLASS, KEY, "3");

        assertEquals(ir.baseUrl + "/getSample?apiKey=" + QUERY_KEY + "&pageNo=3", call.uri().toString());
        assertEquals("종로구", response.at("/body/items/item/0/stationName").asText());
        assertFalse(client().contains("String apiKey"), client());
    }

    @Test
    void 경로_변수의_키는_주소에_경로용으로_인코딩해_넣고_처음_붙는_파라미터를_물음표로_잇는다() throws Throwable {
        TestIr ir = new TestIr();
        ir.endpoint = "/getSample/{appkey}";
        ir.params = new ArrayList<>(List.of(path("appkey"), query("pageNo", false), query("numOfRows", false)));
        ir.auth = new ApiAuth(ApiAuth.PATH, "appkey");

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        String base = ir.baseUrl + "/getSample/" + PATH_KEY;

        call.call(classes, CLIENT_CLASS, KEY, null, null);
        assertEquals(base, call.uri().toString(), "붙일 파라미터가 없으면 물음표도 붙이지 않는다");
        call.call(classes, CLIENT_CLASS, KEY, null, "20");
        assertEquals(base + "?numOfRows=20", call.uri().toString(), "선택 파라미터가 빠져도 처음 붙는 것이 물음표로 시작한다");
        call.call(classes, CLIENT_CLASS, KEY, "1", "20");
        assertEquals(base + "?pageNo=1&numOfRows=20", call.uri().toString());
        assertFalse(client().contains("String appkey"), client());
    }

    @Test
    void 헤더의_키는_요청_헤더로_보내고_주소에_넣지_않는다() throws Throwable {
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("pageNo", true)));
        ir.auth = new ApiAuth(ApiAuth.HEADER, "X-Api-Key");

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        call.call(classes, CLIENT_CLASS, KEY, "1");

        assertEquals(KEY, call.headers().getFirst("X-Api-Key"));
        assertEquals(ir.baseUrl + "/getSample?pageNo=1", call.uri().toString());
    }

    @Test
    void 오류_응답과_파싱_실패의_예외에서_경로와_질의의_키_값을_가린다() throws Throwable {
        // serviceKey가 아닌 자리는 이름으로 찾아 가릴 수 없다. 오류 바디가 요청 주소를 에코하는 기관도 있다
        TestIr pathKey = new TestIr();
        pathKey.endpoint = "/getSample/{appkey}";
        pathKey.params = new ArrayList<>(List.of(path("appkey")));
        pathKey.auth = new ApiAuth(ApiAuth.PATH, "appkey");
        TestIr queryKey = new TestIr();
        queryKey.params = new ArrayList<>(List.of(query("apiKey", true)));
        queryKey.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");
        String echoed = "<response><header><resultCode>30</resultCode><resultMsg>등록되지 않은 키: " + PATH_KEY + ", "
                + QUERY_KEY + ", " + KEY + "</resultMsg></header></response>";

        for (TestIr ir : List.of(pathKey, queryKey)) {
            Path classes = generateAndCompile(ir);

            PublicDataApiException failed = assertThrows(PublicDataApiException.class,
                    () -> new GeneratedClientCall(echoed).call(classes, CLIENT_CLASS, KEY));
            IllegalStateException unreadable = assertThrows(IllegalStateException.class,
                    () -> new GeneratedClientCall("<response><header>").call(classes, CLIENT_CLASS, KEY));

            for (String text : List.of(failed.getMessage(), failed.getRequestUri(), failed.getResultMsg(),
                    failed.getRawBodySnippet(), unreadable.getMessage())) {
                assertFalse(text.contains("K3y"), text);
                assertTrue(text.contains("****"), text);
            }
            assertEquals("30", failed.getResultCode());
        }
    }

    @Test
    void 요청이_네트워크_오류로_실패해도_예외에서_경로와_질의의_키_값을_가린다() throws Throwable {
        // 읽기 시간 초과·연결 거부는 RestTemplate이 요청 주소를 담은 ResourceAccessException으로 바꾼다. 질의 문자열은
        // 빼지만 경로는 남겨, 경로에 넣은 키가 메시지에 그대로 드러났다(제주데이터허브 {your_appkey}). 로그에 원인까지 찍히므로
        // 원인 예외들도 함께 본다
        TestIr pathKey = new TestIr();
        pathKey.endpoint = "/getSample/{appkey}";
        pathKey.params = new ArrayList<>(List.of(path("appkey")));
        pathKey.auth = new ApiAuth(ApiAuth.PATH, "appkey");
        TestIr queryKey = new TestIr();
        queryKey.params = new ArrayList<>(List.of(query("apiKey", true)));
        queryKey.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");

        for (TestIr ir : List.of(pathKey, queryKey)) {
            Path classes = generateAndCompile(ir);

            ResourceAccessException failed = assertThrows(ResourceAccessException.class,
                    () -> GeneratedClientCall.failing(new SocketTimeoutException("Read timed out"))
                            .call(classes, CLIENT_CLASS, KEY));

            String trace = stackTrace(failed);
            assertFalse(trace.contains("K3y"), trace);
            assertTrue(failed.getMessage().contains("Read timed out"), failed.getMessage());
            assertTrue(failed.getCause() instanceof SocketTimeoutException, String.valueOf(failed.getCause()));
        }
    }

    @Test
    void 메서드_인자의_Java_이름이_serviceKey여도_경로의_키는_생성자로_받은_키를_넣는다() throws Throwable {
        TestIr ir = new TestIr();
        ir.endpoint = "/getSample/{appkey}";
        ir.params = new ArrayList<>(List.of(path("appkey"), query("SERVICE_KEY", true)));
        ir.auth = new ApiAuth(ApiAuth.PATH, "appkey");

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        call.call(classes, CLIENT_CLASS, KEY, "arg");

        assertEquals(ir.baseUrl + "/getSample/" + PATH_KEY + "?SERVICE_KEY=arg", call.uri().toString());
        assertTrue(client().contains("encodePath(this.serviceKey)"), client());
    }

    @Test
    void 엔드포인트로_만든_메서드가_키를_받는_defaultRestTemplate과_겹치면_접두사를_붙인다() throws Exception {
        TestIr ir = new TestIr();
        ir.endpoint = "/defaultRestTemplate";
        ir.params = new ArrayList<>(List.of(query("apiKey", true), query("pageNo", true)));
        ir.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");

        generateAndCompile(ir);

        assertTrue(client().contains(" callDefaultRestTemplate("), client());
    }

    @Test
    void api_auth가_serviceKey_질의를_가리키면_api_auth가_없는_IR과_같은_파일을_만든다() throws Exception {
        TestIr declared = new TestIr();
        declared.auth = new ApiAuth(ApiAuth.QUERY, "serviceKey");

        List<Path> legacy = new CodeGenerator().generate(new TestIr().build(), tempDir.resolve("legacy"));
        List<Path> explicit = new CodeGenerator().generate(declared.build(), tempDir.resolve("explicit"));

        for (int i = 0; i < legacy.size(); i++) {
            assertEquals(Files.readString(legacy.get(i)), Files.readString(explicit.get(i)), legacy.get(i).toString());
        }
    }

    @Test
    void OpenAPI_보안_스킴은_실제_키_이름과_자리를_적고_경로의_키는_path_파라미터로_적는다() throws Exception {
        TestIr legacy = new TestIr();
        legacy.params = new ArrayList<>(List.of(query("ServiceKey", true)));
        TestIr header = new TestIr();
        header.params = new ArrayList<>();
        header.auth = new ApiAuth(ApiAuth.HEADER, "X-Api-Key");
        TestIr pathKey = new TestIr();
        pathKey.endpoint = "/getSample/{appkey}";
        pathKey.params = new ArrayList<>(List.of(path("appkey")));
        pathKey.auth = new ApiAuth(ApiAuth.PATH, "appkey");

        JsonNode legacyDoc = openApi(legacy, "legacy");
        JsonNode headerDoc = openApi(header, "header");
        JsonNode pathDoc = openApi(pathKey, "path");

        assertEquals("{\"type\":\"apiKey\",\"name\":\"ServiceKey\",\"in\":\"query\"}",
                legacyDoc.at("/components/securitySchemes/serviceKey").toString(), "문서에 적힌 대소문자 그대로");
        assertEquals(0, legacyDoc.at("/paths/~1getSample/get/parameters").size());
        assertEquals("{\"type\":\"apiKey\",\"name\":\"X-Api-Key\",\"in\":\"header\"}",
                headerDoc.at("/components/securitySchemes/serviceKey").toString());
        assertFalse(pathDoc.has("components"));
        assertFalse(pathDoc.has("security"));
        assertEquals("appkey", pathDoc.at("/paths/~1getSample~1{appkey}/get/parameters/0/name").asText());
    }

    @Test
    void api_auth가_없으면_알려진_인증키_이름의_질의_파라미터를_키로_보낸다() throws Throwable {
        // 농사로 apiKey, 제주 authApiKey, OpenDART crtfc_key. rule 엔진의 IR에는 api.auth가 없다
        for (String name : List.of("apiKey", "authApiKey", "crtfc_key")) {
            TestIr ir = new TestIr();
            ir.params = new ArrayList<>(List.of(query("pageNo", false), query(name, true)));

            Path classes = generateAndCompile(ir);
            GeneratedClientCall call = new GeneratedClientCall(OK);
            call.call(classes, CLIENT_CLASS, KEY, "3");

            assertEquals(ir.baseUrl + "/getSample?" + name + "=" + QUERY_KEY + "&pageNo=3", call.uri().toString());
            assertFalse(client().contains("String " + JavaNames.camel(name)), client());
        }
    }

    @Test
    void api_auth가_없으면_인증키_꼴의_경로_변수에_키를_넣는다() throws Throwable {
        // 제주데이터허브 {your_appkey}, {your_projectKey}
        TestIr ir = new TestIr();
        ir.endpoint = "/getSample/{your_projectKey}";
        ir.params = new ArrayList<>(List.of(path("your_projectKey"), query("pageNo", false)));

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        call.call(classes, CLIENT_CLASS, KEY, "1");

        assertEquals(ir.baseUrl + "/getSample/" + PATH_KEY + "?pageNo=1", call.uri().toString());
        assertFalse(client().contains("String yourProjectKey"), client());
        assertEquals("your_projectKey", openApi(ir, "openapi").at("/paths/~1getSample~1{your_projectKey}/get/parameters/0/name")
                .asText(), "경로의 키는 path 파라미터로 적는다");
    }

    @Test
    void 인증키_파라미터가_없으면_키를_받지_않는_클라이언트를_만든다() throws Throwable {
        // 거제시 rfcapi처럼 키 없이 여는 API. 검색어·도서 키처럼 key로 끝나는 이름은 인증키가 아니라 메서드 인자로 남는다
        TestIr ir = new TestIr();
        ir.baseUrl = "http://data.geoje.go.kr/rfcapi/rest/geojebooks";
        ir.params = new ArrayList<>(List.of(query("geojebooksBookKey", true), query("pageNo", false)));

        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall(OK);
        JsonNode response = call.call(classes, CLIENT_CLASS, null, "B-1", null);

        assertEquals(ir.baseUrl + "/getSample?geojebooksBookKey=B-1", call.uri().toString());
        assertEquals("종로구", response.at("/body/items/item/0/stationName").asText());
        String client = client();
        assertTrue(client.contains("public SampleApiClient() {"), client);
        assertTrue(client.contains("public SampleApiClient(RestTemplate restTemplate) {"), client);
        assertTrue(client.contains("명세에 인증키 파라미터가 없어 인증키 없이 부릅니다."), client);
        assertFalse(client.contains("serviceKey"), client);
        JsonNode doc = openApi(ir, "openapi");
        assertFalse(doc.has("security"), doc.toString());
        assertFalse(doc.has("components"), doc.toString());
        assertEquals(2, doc.at("/paths/~1getSample/get/parameters").size());
    }

    @Test
    void 키를_받지_않는_클라이언트도_오류_응답을_예외로_알린다() throws Throwable {
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("pageNo", true)));
        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall("<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg>"
                + "<returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode>"
                + "</cmmMsgHeader></OpenAPI_ServiceResponse>");

        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> call.call(classes, CLIENT_CLASS, null, "1"));

        assertEquals("30", e.getResultCode());
    }

    @Test
    void 인증키_자리를_정할_수_없는_IR은_이유를_밝혀_거부하고_아무_파일도_쓰지_않는다() throws Exception {
        List<Object[]> cases = List.of(
                new Object[]{new ApiAuth("NONE", "x"), "지원하지 않는 api.auth.kind"},
                new Object[]{new ApiAuth(null, "x"), "지원하지 않는 api.auth.kind"},
                new Object[]{new ApiAuth(ApiAuth.QUERY, " "), "api.auth.name이 없습니다"},
                new Object[]{new ApiAuth(ApiAuth.QUERY, "apiKey"), "query 파라미터가 requestParameters에 없습니다: 'apiKey'"},
                new Object[]{new ApiAuth(ApiAuth.PATH, "pageNo"), "경로 변수가 endpoint에 없습니다: 'pageNo'"},
                new Object[]{new ApiAuth(ApiAuth.HEADER, "X Api Key"), "헤더 이름에 쓸 수 없는 글자"},
                new Object[]{new ApiAuth(ApiAuth.HEADER, "X\"); System.exit(1); //"), "헤더 이름에 쓸 수 없는 글자"});

        for (Object[] c : cases) {
            TestIr ir = new TestIr();
            ir.auth = (ApiAuth) c[0];
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new CodeGenerator().generate(ir.build(), tempDir.resolve("out")));
            assertTrue(e.getMessage().contains((String) c[1]), e.getMessage());
        }
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 포맷_파라미터_힌트가_api_auth의_키를_가리키면_거부한다() {
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("apiKey", true)));
        ir.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");
        IrSpec base = ir.build();
        IrSpec hinted = new IrSpec(base.api(), new GeneratorHints(base.generatorHints().targetPackage(),
                new GeneratorHints.ResponseFormatParameter("apiKey", "xml")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(hinted, tempDir.resolve("out")));

        assertTrue(e.getMessage().contains("인증키가 아닌 쿼리 파라미터여야 합니다"), e.getMessage());
    }

    private Path generateAndCompile(TestIr ir) throws IOException {
        Path out = tempDir.resolve("out");
        List<Path> written = new CodeGenerator().generate(ir.build(), out);
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        return classes;
    }

    private String client() throws IOException {
        return Files.readString(tempDir.resolve("out/kr/go/h2spec/client/sampleapi/SampleApiClient.java"));
    }

    private JsonNode openApi(TestIr ir, String name) throws IOException {
        List<Path> written = new CodeGenerator().generate(ir.build(), tempDir.resolve(name));
        return new ObjectMapper().readTree(written.get(2).toFile());
    }

    private static String stackTrace(Throwable e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).map(dir::relativize).toList();
        }
    }
}
