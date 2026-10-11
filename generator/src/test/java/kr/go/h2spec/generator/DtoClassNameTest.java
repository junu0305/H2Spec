package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import kr.go.h2spec.generator.emit.JavaNames;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.ir.ResponseField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DTO 중첩 클래스 이름이 겹치면 이름을 바꿔 생성한다(설계 3-7). 생성 코드가 컴파일되고,
 * 클래스 이름이 바뀌어도 Jackson이 JSON·XML의 원래 키를 읽는지 본다. 생성 클라이언트와 같은 매퍼 설정으로 읽는다.
 */
class DtoClassNameTest {

    private static final String DTO_CLASS = "kr.go.h2spec.client.sampleapi.dto.SampleApiResponse";
    /** 리스트 마디를 고르는 경로 표기. "item[1]"은 item 리스트의 두 번째 원소다 */
    private static final Pattern INDEXED = Pattern.compile("(.+)\\[(\\d+)]");

    @TempDir
    Path tempDir;

    private URLClassLoader loader;

    @AfterEach
    void closeLoader() throws IOException {
        if (loader != null) {
            loader.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void result_list_경로의_List_클래스는_이름을_바꾸고_원래_키를_읽는다(String format) throws Exception {
        // 실제 계층을 따르는 llm 경로에서 생긴다. List라는 중첩 클래스는 List 필드 타입을 가린다
        String dto = generateAndCompile(format, List.of(
                field("result.totalCount", "integer"),
                field("result.list[].stationName", "string")));

        assertFalse(dto.contains("public static class List {"), dto);
        assertTrue(dto.contains("public static class ResultList {"), dto);
        assertTrue(dto.contains("private List<ResultList> list;"), dto);
        Object response = read(format, "XML".equals(format)
                ? "<result><totalCount>2</totalCount>"
                        + "<list><stationName>종로구</stationName></list>"
                        + "<list><stationName>중구</stationName></list></result>"
                : "{\"result\":{\"totalCount\":2,"
                        + "\"list\":[{\"stationName\":\"종로구\"},{\"stationName\":\"중구\"}]}}");
        assertEquals(2L, get(response, "totalCount"));
        assertEquals("종로구", get(response, "list[0].stationName"));
        assertEquals("중구", get(response, "list[1].stationName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void 서로_다른_두_item_컨테이너는_뒤_클래스_이름을_바꾸고_원래_키를_읽는다(String format) throws Exception {
        // 파일에 먼저 나오는 body.item이 Item을 갖고, 뒤의 items.item은 부모 이름을 붙여 ItemsItem이 된다
        String dto = generateAndCompile(format, List.of(
                field("response.header.resultCode", "string"),
                field("response.body.item.totalCount", "integer"),
                field("response.body.items.item[].stationName", "string")));

        assertTrue(dto.contains("public static class Item {"), dto);
        assertTrue(dto.contains("public static class ItemsItem {"), dto);
        assertTrue(dto.contains("private Item item;"), dto);
        assertTrue(dto.contains("private List<ItemsItem> item;"), dto);
        Object response = read(format, "XML".equals(format)
                ? "<response><header><resultCode>00</resultCode></header><body>"
                        + "<item><totalCount>2</totalCount></item>"
                        + "<items><item><stationName>종로구</stationName></item>"
                        + "<item><stationName>중구</stationName></item></items></body></response>"
                : "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{"
                        + "\"item\":{\"totalCount\":2},"
                        + "\"items\":{\"item\":[{\"stationName\":\"종로구\"},{\"stationName\":\"중구\"}]}}}}");
        assertEquals(2L, get(response, "body.item.totalCount"));
        assertEquals("중구", get(response, "body.items.item[1].stationName"));
        if ("JSON".equals(format)) {
            // items가 래퍼 객체 없이 배열로 오는 기관도 바뀐 이름의 클래스로 받는다(ofArray)
            Object arrayForm = read(format, "{\"response\":{\"body\":{\"items\":"
                    + "[{\"stationName\":\"종로구\"},{\"stationName\":\"중구\"}]}}}");
            assertEquals("중구", get(arrayForm, "body.items.item[1].stationName"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void 생성_코드가_쓰는_타입_이름과_같은_컨테이너도_컴파일되고_원래_키를_읽는다(String format) throws Exception {
        // 중첩 클래스 이름은 루트 클래스 안에서 같은 이름의 import를 가린다. JsonProperty라는 클래스가 있으면 @JsonProperty가 그 클래스를 가리킨다.
        // items.item[]을 함께 두어 두 포맷 모두 리스트·배열 수용 팩토리의 어노테이션을 실제로 쓰게 한다.
        // Long은 필드 이름(long)이 예약어라 생성기가 먼저 거부하므로, 필드 이름은 쓸 수 있고 클래스 이름만 Long이 되는 _Long으로 넣는다
        List<String> names = List.of("JsonProperty", "JsonIgnoreProperties", "JsonCreator", "JsonRootName",
                "JacksonXmlRootElement", "JacksonXmlProperty", "JacksonXmlElementWrapper",
                "List", "String", "_Long", "_Double", "_Boolean", "SampleApiResponse");
        List<ResponseField> fields = new ArrayList<>(List.of(field("response.body.items.item[].stationName", "string")));
        StringBuilder xml = new StringBuilder("<response><body><items><item><stationName>종로구</stationName></item></items>");
        StringBuilder json = new StringBuilder("{\"response\":{\"body\":{\"items\":{\"item\":[{\"stationName\":\"종로구\"}]}");
        for (String name : names) {
            fields.add(field("response.body." + name + ".value", "string"));
            xml.append("<").append(name).append("><value>").append(name).append(" 값</value></").append(name).append(">");
            json.append(",\"").append(name).append("\":{\"value\":\"").append(name).append(" 값\"}");
        }
        xml.append("</body></response>");
        json.append("}}}");

        String dto = generateAndCompile(format, fields);

        for (String name : names) {
            String className = JavaNames.pascal(name);
            assertFalse(dto.contains("public static class " + className + " {"), name + "\n" + dto);
            assertTrue(dto.contains("public static class Body" + className + " {"), name + "\n" + dto);
        }
        Object response = read(format, "XML".equals(format) ? xml.toString() : json.toString());
        for (String name : names) {
            assertEquals(name + " 값", get(response, "body." + name + ".value"), name);
        }
        assertEquals("종로구", get(response, "body.items.item[0].stationName"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"XML", "JSON"})
    void javac나_파일_시스템이_같게_보는_클래스_이름도_겹치지_않게_만든다(String format) throws Exception {
        // ITEMS는 대소문자를 가리지 않는 파일 시스템에서 Items와 class 파일을 함께 쓰고,
        // soft hyphen(U+00AD)이 든 이름은 javac가 그 글자를 빼고 읽어 Items와 같은 클래스가 된다
        List<Path> written = generate(format, List.of(
                field("response.header.items.a", "string"),
                field("response.body.ITEMS.b", "string"),
                field("response.tail.ite\u00ADms.c", "string")));

        List<String> classFiles = assertCompiles(written, classes());

        Set<String> caseInsensitive = classFiles.stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertEquals(classFiles.size(), caseInsensitive.size(), classFiles.toString());
    }

    /** 생성한 DTO를 컴파일하고 DTO 소스를 돌려준다 */
    private String generateAndCompile(String format, List<ResponseField> fields) throws Exception {
        List<Path> written = generate(format, fields);
        assertCompiles(written, classes());
        return Files.readString(written.get(0));
    }

    private List<Path> generate(String format, List<ResponseField> fields) throws IOException {
        IrSpec ir = new IrSpec(
                new ApiSpec("SampleApi", "샘플 조회", "샘플 자료를 조회한다",
                        "http://apis.data.go.kr/1234567/SampleService", "/getSample", "GET", format,
                        List.of(new RequestParameter("serviceKey", "query", "string", true, "인증키", null, null, null)),
                        fields, new ErrorSpec("00")),
                new GeneratorHints("kr.go.h2spec.client.sampleapi"));
        return new CodeGenerator().generate(ir, tempDir.resolve("out"));
    }

    /** 컴파일한 DTO로 응답을 읽는다. 생성 클라이언트처럼 XML은 XmlMapper, JSON은 루트를 벗기는 ObjectMapper로 읽는다 */
    private Object read(String format, String body) throws Exception {
        if (loader == null) {
            loader = new URLClassLoader(new URL[]{classes().toUri().toURL()}, getClass().getClassLoader());
        }
        ObjectMapper mapper = "XML".equals(format)
                ? new XmlMapper()
                : new ObjectMapper().enable(DeserializationFeature.UNWRAP_ROOT_VALUE);
        return mapper.readValue(body, loader.loadClass(DTO_CLASS));
    }

    /** 루트 클래스부터 응답 경로를 따라 getter로 값을 꺼낸다. getter 이름은 생성기처럼 원래 이름의 PascalCase다 */
    private static Object get(Object bean, String path) throws Exception {
        Object current = bean;
        for (String segment : path.split("\\.")) {
            Matcher indexed = INDEXED.matcher(segment);
            String name = indexed.matches() ? indexed.group(1) : segment;
            current = current.getClass().getMethod("get" + JavaNames.pascal(name)).invoke(current);
            if (indexed.matches()) {
                current = ((List<?>) current).get(Integer.parseInt(indexed.group(2)));
            }
        }
        return current;
    }

    private Path classes() {
        return tempDir.resolve("classes");
    }

    private static ResponseField field(String path, String type) {
        return new ResponseField(path, type, null, null);
    }
}
