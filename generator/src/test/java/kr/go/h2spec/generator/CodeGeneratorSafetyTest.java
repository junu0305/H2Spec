package kr.go.h2spec.generator;

import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.ir.ResponseField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.GeneratedSourceCompiler.javap;
import static kr.go.h2spec.generator.GoldenFileAssertions.assertEqualsIgnoringLineEndings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * generator 안전장치. IR의 글로 생성 코드에 코드를 끼워 넣거나, 컴파일되지 않는 코드나
 * 출력 디렉터리 밖의 파일을 만들 수 없는지 본다. 끼워 넣기는 무력화된 뒤 생성 코드가 실제로 컴파일되는지까지 본다.
 */
class CodeGeneratorSafetyTest {

    private static final String CLIENT_CLASS = "kr.go.h2spec.client.sampleapi.SampleApiClient";

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void 생성_코드에_글이_들어가는_모든_자리에서_코드를_끼워_넣지_못한다(String responseFormat) throws Exception {
        // 주석에 들어가는 글(이름·설명)과 리터럴에 들어가는 글(주소·성공코드)에 직접 쓴 것과 escape로 쓴 것을 함께 넣는다
        String comment = "*/ class Injected {} /* \\u002a\\u002f class InjectedByEscape {} /*";
        String literal = "\"; static { System.exit(1); } // \\u0022; static { System.exit(2); } //";
        SampleIr ir = new SampleIr();
        ir.responseFormat = responseFormat;
        ir.apiName = "샘플 " + comment;
        ir.description = "설명\n" + comment;
        ir.baseUrl = "http://apis.data.go.kr/1234567/SampleService" + literal;
        ir.endpoint = "/getSample" + literal;
        ir.successCode = "00" + literal;
        ir.params = new ArrayList<>(List.of(
                serviceKey("인증키 " + comment),
                query("pageNo", "페이지 번호 " + comment)));
        ir.fields = new ArrayList<>(List.of(
                field("response.header.resultCode", "결과코드 " + comment),
                field("response.body.items.item[].stationName", "측정소명\r\n" + comment)));
        // 결과코드 후보는 경로의 마지막 마디만 들어가므로 점이 없는 글로 넣는다
        ir.resultCodeCandidates = List.of(
                "response.header.resultCd\"; static { new Object(); } // \\u0022; static { new Object(); } //");

        List<Path> written = new CodeGenerator().generate(ir.build(), out());
        List<String> classNames = assertCompiles(written, classes());

        assertTrue(Files.readString(written.get(1)).contains("List.of(\"resultCd\\\"; static"), "결과코드 후보가 들어가야 한다");
        assertTrue(classNames.stream().noneMatch(name -> name.contains("Injected")), classNames.toString());
        assertFalse(javap(classes(), CLIENT_CLASS).contains("static {}"), "static 초기화 블록이 생기면 안 된다");
    }

    @Test
    void apiId에_상위_경로가_있으면_거부하고_출력_디렉터리_밖에_아무것도_쓰지_않는다() throws Exception {
        // apiId로 파일 경로를 만들므로 그대로 쓰면 출력 디렉터리 밖에 OpenAPI 파일이 써진다
        SampleIr ir = new SampleIr();
        ir.apiId = "../../escaped";
        Path outputDir = tempDir.resolve("out").resolve("nested");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), outputDir));

        assertTrue(e.getMessage().contains("api.apiId"), e.getMessage());
        assertTrue(e.getMessage().contains("../../escaped"), e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 포맷_파라미터_힌트의_값에_URL에_넣을_수_없는_글자가_있으면_거부한다() throws Exception {
        // 값은 인코딩 없이 URL에 들어간다
        SampleIr ir = new SampleIr();
        ir.params.add(query("type", "xml 또는 json"));
        ir.formatParameter = new GeneratorHints.ResponseFormatParameter("type", "xml&serviceKey=x");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), out()));

        assertTrue(e.getMessage().contains("generatorHints.responseFormatParameter.value"), e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 포맷_파라미터_힌트가_경로_변수나_serviceKey를_가리키면_거부한다() throws Exception {
        SampleIr pathHint = new SampleIr();
        pathHint.endpoint = "/getSample/{type}";
        pathHint.params.add(new RequestParameter("type", "path", "string", true, "형식", null, null, null));
        pathHint.formatParameter = new GeneratorHints.ResponseFormatParameter("type", "xml");
        SampleIr keyHint = new SampleIr();
        keyHint.formatParameter = new GeneratorHints.ResponseFormatParameter("serviceKey", "xml");

        for (SampleIr ir : List.of(pathHint, keyHint)) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new CodeGenerator().generate(ir.build(), out()));
            assertTrue(e.getMessage().contains("serviceKey가 아닌 쿼리 파라미터여야 합니다"), e.getMessage());
        }
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 숫자로_시작하는_엔드포인트는_접두사를_붙인_메서드명으로_컴파일된다() throws Exception {
        // jejudatahub처럼 주소 끝에 경로 변수가 있는 문서. 엔드포인트를 그대로 이으면 7tttadad5…YourAppkey가 되어 컴파일되지 않는다
        SampleIr ir = new SampleIr();
        ir.endpoint = "/7tttaDaD5ataaba75a57ttDtaDb6tb7t/{your_appkey}";
        ir.params.add(new RequestParameter("your_appkey", "path", "string", true, "앱 키", null, null, null));

        List<Path> written = new CodeGenerator().generate(ir.build(), out());

        assertTrue(Files.readString(written.get(1))
                .contains(" call7tttadad5ataaba75a57ttdtadb6tb7tYourAppkey("), Files.readString(written.get(1)));
        assertCompiles(written, classes());
    }

    @Test
    void 엔드포인트로_만든_메서드가_생성_헬퍼나_Object의_메서드와_겹치면_접두사를_붙이고_겹치지_않으면_그대로_둔다() throws Exception {
        // /encode에 문자열 인자 하나면 생성 헬퍼 encode(String)와, 인자 없는 /getClass는 Object의 final 메서드와 겹친다
        SampleIr encode = new SampleIr();
        encode.endpoint = "/encode";
        SampleIr getClass = new SampleIr();
        getClass.endpoint = "/getClass";
        getClass.params = new ArrayList<>(List.of(serviceKey("인증키")));
        SampleIr overload = new SampleIr();
        overload.endpoint = "/toString";

        List<Path> encoded = new CodeGenerator().generate(encode.build(), tempDir.resolve("encode"));
        List<Path> classed = new CodeGenerator().generate(getClass.build(), tempDir.resolve("get-class"));
        List<Path> overloaded = new CodeGenerator().generate(overload.build(), tempDir.resolve("overload"));

        assertTrue(Files.readString(encoded.get(1)).contains(" callEncode("), Files.readString(encoded.get(1)));
        assertTrue(Files.readString(classed.get(1)).contains(" callGetClass("), Files.readString(classed.get(1)));
        assertTrue(Files.readString(overloaded.get(1)).contains(" toString(\n"), "인자가 달라 겹치지 않는 이름은 바꾸지 않는다");
        assertCompiles(encoded, tempDir.resolve("encode-classes"));
        assertCompiles(classed, tempDir.resolve("get-class-classes"));
        assertCompiles(overloaded, tempDir.resolve("overload-classes"));
    }

    @Test
    void 메서드_인자의_Java_이름이_serviceKey가_되어도_인증키는_생성자로_받은_키를_보낸다() throws Exception {
        // 게이트웨이 문서가 SERVICE_KEY를 따로 적으면 rule 엔진은 serviceKey를 하나 더 채운다. 인자 serviceKey가 필드를 가린다
        SampleIr ir = new SampleIr();
        ir.params.add(query("SERVICE_KEY", "문서에 적힌 다른 인증키 칸"));

        List<Path> written = new CodeGenerator().generate(ir.build(), out());

        String client = Files.readString(written.get(1));
        assertTrue(client.contains("url.append(\"?serviceKey=\").append(encode(this.serviceKey));"), client);
        assertTrue(client.contains("url.append(\"&SERVICE_KEY=\").append(encode(serviceKey));"), client);
        assertCompiles(written, classes());
    }

    @Test
    void getter가_getClass가_되는_응답_필드와_UTF8로_쓸_수_없는_글은_이유를_밝혀_거부한다() throws Exception {
        SampleIr classField = new SampleIr();
        classField.fields.add(field("response.body.items.item[]._class", "구분"));
        SampleIr surrogate = new SampleIr();
        surrogate.description = "설명\uD800";

        IllegalArgumentException getter = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(classField.build(), out()));
        IllegalArgumentException encoding = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(surrogate.build(), out()));

        assertTrue(getter.getMessage().contains("Object.getClass()"), getter.getMessage());
        assertTrue(encoding.getMessage().contains("짝 없는 서로게이트"), encoding.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void Java_이름이_겹치는_응답_필드는_거부한다() throws Exception {
        // 원래 이름은 달라도 같은 필드 이름(stationName)이 되어 "already defined"로 컴파일되지 않는다
        SampleIr ir = new SampleIr();
        ir.fields.add(field("response.body.items.item[].station_name", "측정소명"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), out()));

        assertTrue(e.getMessage().contains("response.body.items.item.stationName"), e.getMessage());
        assertTrue(e.getMessage().contains("response.body.items.item.station_name"), e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void getter_이름이_겹치는_응답_필드는_거부한다() throws Exception {
        // 필드 이름은 sido와 Sido로 다르지만 getter·setter가 둘 다 getSido·setSido가 되어 "already defined"로 컴파일되지 않는다
        SampleIr ir = new SampleIr();
        ir.fields.add(field("response.body.items.item[].sido", "시도"));
        ir.fields.add(field("response.body.items.item[]._sido", "시도 구분"));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), out()));

        assertTrue(e.getMessage().contains("'getSido'"), e.getMessage());
        assertTrue(e.getMessage().contains("response.body.items.item.sido, response.body.items.item._sido"),
                e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @Test
    void 생성에_실패하면_아무_파일도_쓰지_않는다() throws Exception {
        // DTO를 먼저 써 두면 ClientEmitter가 serviceKey가 없어 실패할 때 DTO 파일만 남는다
        SampleIr ir = new SampleIr();
        ir.params = new ArrayList<>(List.of(query("pageNo", "페이지 번호")));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CodeGenerator().generate(ir.build(), out()));

        assertTrue(e.getMessage().contains("serviceKey"), e.getMessage());
        assertEquals(List.of(), filesUnder(tempDir));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void 공백_한글_하이픈_달러가_든_이름은_지금처럼_생성되고_컴파일된다(String responseFormat) throws Exception {
        // JavaNames가 Java 이름으로 바꾸고 어노테이션과 URL은 원래 이름을 지킨다. 컴파일되는 이름은 막지 않는다.
        // URL의 질의에는 공백을 그대로 넣으면 호출할 때 URI.create가 실패하므로 인코딩해 넣는다. 한글은 URI.create가 받아 그대로 둔다
        SampleIr ir = new SampleIr();
        ir.responseFormat = responseFormat;
        for (String name : List.of("relevant departments", "측정소", "sido-name", "a$b")) {
            ir.params.add(query(name, "설명"));
            ir.fields.add(field("response.body.items.item[]." + name, "설명"));
        }

        List<Path> written = new CodeGenerator().generate(ir.build(), out());

        assertCompiles(written, classes());
        String client = Files.readString(written.get(1));
        assertTrue(client.contains("url.append(\"&relevant%20departments=\")"), client);
        assertTrue(client.contains("url.append(\"&측정소=\")"), client);
        assertTrue(client.contains("url.append(\"&a$b=\")"), client);
        assertTrue(Files.readString(written.get(0)).contains("\"sido-name\""));
    }

    @Test
    void 골든_IR은_그대로_통과하고_골든파일과_같은_파일을_쓴다() throws Exception {
        // 검사를 거치고 메모리에서 만든 뒤에 써도 생성 결과는 golden 파일과 한 글자도 다르지 않아야 한다
        List<Path> written = new CodeGenerator().generate(resource("/ir/schema-example.json"), out());

        assertEqualsIgnoringLineEndings(
                Files.readString(resource("/golden/RTMSDataSvcAptTradeDevResponse.java.txt")),
                Files.readString(written.get(0)));
        assertEqualsIgnoringLineEndings(
                Files.readString(resource("/golden/RTMSDataSvcAptTradeDevClient.java.txt")),
                Files.readString(written.get(1)));
        assertEqualsIgnoringLineEndings(
                Files.readString(resource("/golden/RTMSDataSvcAptTradeDevOpenApi.json")),
                Files.readString(written.get(2)));
    }

    private Path out() {
        return tempDir.resolve("out");
    }

    private Path classes() {
        return tempDir.resolve("classes");
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }

    private static List<Path> filesUnder(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            return paths.filter(Files::isRegularFile).map(dir::relativize).toList();
        }
    }

    private static RequestParameter serviceKey(String description) {
        return new RequestParameter("serviceKey", "query", "string", true, description, null, null, null);
    }

    private static RequestParameter query(String name, String description) {
        return new RequestParameter(name, "query", "string", false, description, null, null, null);
    }

    private static ResponseField field(String path, String description) {
        return new ResponseField(path, "string", description, null);
    }

    /** 검사를 모두 통과하는 IR. 테스트마다 바꿀 곳만 바꾼다 */
    private static final class SampleIr {
        String apiId = "SampleApi";
        String apiName = "샘플 조회";
        String description = "샘플 자료를 조회한다";
        String baseUrl = "http://apis.data.go.kr/1234567/SampleService";
        String endpoint = "/getSample";
        String responseFormat = "XML";
        String successCode = "00";
        String targetPackage = "kr.go.h2spec.client.sampleapi";
        List<RequestParameter> params = new ArrayList<>(List.of(
                serviceKey("인증키"),
                query("pageNo", "페이지 번호")));
        List<ResponseField> fields = new ArrayList<>(List.of(
                field("response.header.resultCode", "결과코드"),
                field("response.body.items.item[].stationName", "측정소명")));
        List<String> resultCodeCandidates = List.of();
        GeneratorHints.ResponseFormatParameter formatParameter;

        IrSpec build() {
            return new IrSpec(
                    new ApiSpec(apiId, apiName, description, baseUrl, endpoint, "GET", responseFormat,
                            params, fields, new ErrorSpec(successCode, resultCodeCandidates)),
                    new GeneratorHints(targetPackage, formatParameter));
        }
    }
}
