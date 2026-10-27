package kr.go.h2spec.generator;

import com.fasterxml.jackson.databind.JsonNode;
import kr.go.h2spec.client.interceptor.PublicDataApiException;
import kr.go.h2spec.generator.ir.ApiAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static kr.go.h2spec.generator.TestIr.CLIENT_CLASS;
import static kr.go.h2spec.generator.TestIr.field;
import static kr.go.h2spec.generator.TestIr.query;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 문서에는 없지만 실제 공공데이터 응답에서 흔한 꼴과 사용자가 흔히 넣는 키. 컴파일한 클라이언트를 불러 응답 전체를 읽지 못하게 되지 않는지 본다.
 */
class ResponseVariantGenerationTest {

    @TempDir
    Path tempDir;

    @Test
    void JSON_응답은_결과가_1건이라_객체로_와도_결과가_없어_빈_글로_와도_읽는다() throws Throwable {
        // XML을 JSON으로 옮겨 주는 기관은 반복 요소가 1건이면 배열 대신 객체를, 0건이면 빈 글을 준다
        TestIr ir = new TestIr();
        ir.responseFormat = "JSON";
        ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                field("response.body.totalCount", "integer"), field("response.body.items.item[].stationName", "string")));

        Path classes = generateAndCompile(ir);
        JsonNode one = call(classes, "{\"response\": {\"header\": {\"resultCode\": \"00\"}, \"body\": {\"totalCount\": 1,"
                + " \"items\": {\"item\": {\"stationName\": \"종로구\"}}}}}");
        JsonNode none = call(classes, "{\"response\": {\"header\": {\"resultCode\": \"00\"}, \"body\": {\"totalCount\": 0,"
                + " \"items\": \"\"}}}");

        assertEquals("종로구", one.at("/body/items/item/0/stationName").asText());
        assertEquals(1, one.at("/body/items/item").size());
        assertTrue(none.at("/body/items/item").isNull(), "XML의 빈 <items/>처럼 목록이 null이다: " + none);
    }

    @Test
    void 루트_없는_JSON_응답도_같은_옵션으로_읽고_XML_클라이언트에는_넣지_않는다() throws IOException {
        TestIr rootless = new TestIr();
        rootless.responseFormat = "JSON";
        rootless.rootless = true;
        rootless.fields = new ArrayList<>(List.of(field("totCnt", "integer"), field("data[].baseDate", "string")));

        String json = Files.readString(new CodeGenerator().generate(rootless.build(), tempDir.resolve("json")).get(1));
        String xml = Files.readString(new CodeGenerator().generate(new TestIr().build(), tempDir.resolve("xml")).get(1));

        assertTrue(json.contains(".enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)"), json);
        assertTrue(json.contains(".enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT);"), json);
        assertFalse(json.contains("UNWRAP_ROOT_VALUE"), json);
        assertFalse(xml.contains("DeserializationFeature"), "XML의 반복 요소는 건수와 상관없이 같은 모양이다");
    }

    @Test
    void 숫자와_불리언_칸은_흔한_꼴을_읽고_읽을_수_없는_글은_응답_전체를_실패시키지_않고_null로_둔다() throws Throwable {
        // 측정값이 없을 때의 "-", 금액의 천 단위 쉼표, 앞뒤 공백, 빈 칸, Y·N. XML은 모든 값이 글이다
        for (String format : List.of("XML", "JSON")) {
            TestIr ir = new TestIr();
            ir.responseFormat = format;
            ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                    field("response.body.items.item[].pm10Value", "integer"),
                    field("response.body.items.item[].so2Value", "number"),
                    field("response.body.items.item[].dealAmount", "integer"),
                    field("response.body.items.item[].isHoliday", "boolean")));
            String[][] items = {
                    {"-", "0.003", "    82,500", "Y"},
                    {"", "-", "1,5", "N"},
                    {" 30 ", "1,234.5", "82500", "true"},
                    {"7", "", "", "통신장애"}};

            Path classes = generateAndCompile(ir);
            JsonNode response = call(classes, format.equals("XML") ? xml(items) : json(items));
            JsonNode item = response.at("/body/items/item");

            assertEquals(4, item.size(), format);
            assertTrue(item.get(0).get("pm10Value").isNull(), format + " \"-\"는 값이 없다");
            assertEquals(0.003, item.get(0).get("so2Value").asDouble(), format);
            assertEquals(82500, item.get(0).get("dealAmount").asLong(), format + " 천 단위 쉼표와 앞 공백");
            assertTrue(item.get(0).get("isHoliday").asBoolean(), format);
            assertTrue(item.get(1).get("pm10Value").isNull(), format + " 빈 칸");
            assertTrue(item.get(1).get("so2Value").isNull(), format);
            assertTrue(item.get(1).get("dealAmount").isNull(), format + " 천 단위 꼴이 아닌 쉼표는 다른 값으로 읽지 않는다");
            assertFalse(item.get(1).get("isHoliday").asBoolean(true), format);
            assertEquals(30, item.get(2).get("pm10Value").asLong(), format);
            assertEquals(1234.5, item.get(2).get("so2Value").asDouble(), format);
            assertEquals(82500, item.get(2).get("dealAmount").asLong(), format);
            assertTrue(item.get(2).get("isHoliday").asBoolean(), format);
            assertEquals(7, item.get(3).get("pm10Value").asLong(), format);
            assertTrue(item.get(3).get("isHoliday").isNull(), format);
        }
    }

    @Test
    void JSON의_숫자와_불리언_값은_그대로_읽고_값이_아닌_것이_오면_지금처럼_실패한다() throws Throwable {
        TestIr ir = new TestIr();
        ir.responseFormat = "JSON";
        ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                field("response.body.totalCount", "integer"), field("response.body.ratio", "number"),
                field("response.body.hasMore", "boolean")));

        Path classes = generateAndCompile(ir);
        JsonNode response = call(classes, "{\"response\": {\"header\": {\"resultCode\": \"00\"},"
                + " \"body\": {\"totalCount\": 13702, \"ratio\": 0.5, \"hasMore\": true}}}");

        assertEquals(13702, response.at("/body/totalCount").asLong());
        assertEquals(0.5, response.at("/body/ratio").asDouble());
        assertTrue(response.at("/body/hasMore").asBoolean());
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> call(classes,
                "{\"response\": {\"header\": {\"resultCode\": \"00\"}, \"body\": {\"totalCount\": {\"value\": 1}}}}"));
        assertTrue(e.getMessage().startsWith("응답 파싱 실패"), e.getMessage());
    }

    @Test
    void 정수_칸에_소수가_오면_형식과_상관없이_null로_두고_소수점_아래가_0이면_정수로_읽는다() throws Throwable {
        // 칸 타입은 문서의 샘플값으로 정한다. 샘플이 정수였던 칸에 소수가 오면 JSON 숫자는 소수점 아래를 버리고(2.5 → 2)
        // XML 글은 null이 되어, 같은 응답이 형식에 따라 다르게 읽혔다(기상청 중기해상예보의 파고)
        List<String> values = List.of("2.5", "3.0", "4");
        for (String format : List.of("XML", "JSON")) {
            TestIr ir = new TestIr();
            ir.responseFormat = format;
            ir.fields = new ArrayList<>(List.of(field("response.header.resultCode", "string"),
                    field("response.body.items.item[].waveHeight", "integer")));
            StringBuilder body = new StringBuilder(format.equals("XML")
                    ? "<response><header><resultCode>00</resultCode></header><body><items>"
                    : "{\"response\": {\"header\": {\"resultCode\": \"00\"}, \"body\": {\"items\": {\"item\": [");
            for (int i = 0; i < values.size(); i++) {
                body.append(format.equals("XML")
                        ? "<item><waveHeight>" + values.get(i) + "</waveHeight></item>"
                        : (i > 0 ? ", " : "") + "{\"waveHeight\": " + values.get(i) + "}");
            }
            body.append(format.equals("XML") ? "</items></body></response>" : "]}}}}");

            Path classes = generateAndCompile(ir);
            JsonNode item = call(classes, body.toString()).at("/body/items/item");

            assertTrue(item.get(0).get("waveHeight").isNull(), format + " 2.5는 정수가 아니다: " + item);
            assertEquals(3, item.get(1).get("waveHeight").asLong(), format + " 3.0: " + item);
            assertEquals(4, item.get(2).get("waveHeight").asLong(), format + " 4: " + item);
        }
    }

    @Test
    void 응답_필드_이름이_역직렬화기가_쓰는_이름과_같아도_중첩_클래스_이름을_바꿔_컴파일된다() throws Exception {
        // 중첩 클래스 이름은 루트 클래스 안에서 같은 이름의 타입을 가린다. Override면 @Override가, LenientLong이면 어노테이션이 엉뚱한 타입을 가리킨다
        TestIr ir = new TestIr();
        for (String name : List.of("override", "lenientLong", "jsonParser", "numberFormatException")) {
            ir.fields.add(field("response.body." + name + ".count", "integer"));
        }

        List<String> classNames = assertCompiles(new CodeGenerator().generate(ir.build(), tempDir.resolve("out")),
                tempDir.resolve("classes"));

        assertTrue(classNames.contains("SampleApiResponse$BodyOverride.class"), classNames.toString());
        assertTrue(classNames.contains("SampleApiResponse$BodyLenientLong.class"), classNames.toString());
        assertTrue(classNames.contains("SampleApiResponse$LenientLong.class"), classNames.toString());
    }

    @Test
    void 공공데이터포털의_Encoding_키를_넣어도_다시_인코딩하지_않는다() throws Throwable {
        // 포털은 같은 키를 Encoding 키와 Decoding 키 두 가지로 준다. Encoding 키를 다시 인코딩하면 등록되지 않은 키(30)가 된다
        Path classes = generateAndCompile(new TestIr());
        String ok = "<response><header><resultCode>00</resultCode></header></response>";

        GeneratedClientCall decoded = new GeneratedClientCall(ok);
        decoded.call(classes, CLIENT_CLASS, "Ab+c/D==", (Object) null);
        GeneratedClientCall encoded = new GeneratedClientCall(ok);
        encoded.call(classes, CLIENT_CLASS, "Ab%2Bc%2FD%3D%3D", (Object) null);

        String expected = "http://apis.data.go.kr/1234567/SampleService/getSample?serviceKey=Ab%2Bc%2FD%3D%3D";
        assertEquals(expected, decoded.uri().toString());
        assertEquals(expected, encoded.uri().toString(), "%2B를 %252B로 다시 인코딩하지 않는다");
    }

    @Test
    void 이름이_다른_쿼리_키에_Encoding_키를_넣어도_다시_인코딩하지_않고_예외에서_가린다() throws Throwable {
        TestIr ir = new TestIr();
        ir.params = new ArrayList<>(List.of(query("apiKey", true)));
        ir.auth = new ApiAuth(ApiAuth.QUERY, "apiKey");
        Path classes = generateAndCompile(ir);
        GeneratedClientCall call = new GeneratedClientCall("<response><header><resultCode>30</resultCode></header></response>");

        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> call.call(classes, CLIENT_CLASS, "Ab%2Bc%2FD%3D%3D"));

        assertEquals("http://apis.data.go.kr/1234567/SampleService/getSample?apiKey=Ab%2Bc%2FD%3D%3D", call.uri().toString());
        assertFalse(e.getMessage().contains("Ab%2B"), e.getMessage());
        assertTrue(e.getRequestUri().endsWith("apiKey=****"), e.getRequestUri());
    }

    private static String xml(String[][] items) {
        StringBuilder sb = new StringBuilder("<response><header><resultCode>00</resultCode></header><body><items>");
        for (String[] item : items) {
            sb.append("<item><pm10Value>").append(item[0]).append("</pm10Value><so2Value>").append(item[1])
              .append("</so2Value><dealAmount>").append(item[2]).append("</dealAmount><isHoliday>").append(item[3])
              .append("</isHoliday></item>");
        }
        return sb.append("</items></body></response>").toString();
    }

    private static String json(String[][] items) {
        StringBuilder sb = new StringBuilder("{\"response\": {\"header\": {\"resultCode\": \"00\"}, \"body\": {\"items\": {\"item\": [");
        for (int i = 0; i < items.length; i++) {
            String[] item = items[i];
            sb.append(i > 0 ? ", " : "").append("{\"pm10Value\": \"").append(item[0]).append("\", \"so2Value\": \"")
              .append(item[1]).append("\", \"dealAmount\": \"").append(item[2]).append("\", \"isHoliday\": \"")
              .append(item[3]).append("\"}");
        }
        return sb.append("]}}}}").toString();
    }

    /** TestIr의 기본 파라미터(serviceKey, 선택 pageNo)로 부른다 */
    private static JsonNode call(Path classes, String body) throws Throwable {
        return new GeneratedClientCall(body).call(classes, CLIENT_CLASS, "key", (Object) null);
    }

    private Path generateAndCompile(TestIr ir) throws IOException {
        Path out = Files.createTempDirectory(tempDir, "out");
        List<Path> written = new CodeGenerator().generate(ir.build(), out);
        Path classes = Files.createTempDirectory(tempDir, "classes");
        assertCompiles(written, classes);
        return classes;
    }
}
