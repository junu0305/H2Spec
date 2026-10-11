package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.ResponseField;
import kr.go.h2spec.generator.model.DtoNode;
import kr.go.h2spec.generator.model.DtoTreeBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static kr.go.h2spec.generator.GoldenFileAssertions.assertEqualsIgnoringLineEndings;

class DtoEmitterTest {

    @Test
    void 골든파일과_일치한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String actual = new DtoEmitter().emit(ir, root);

        String expected = Files.readString(resource("/golden/RTMSDataSvcAptTradeDevResponse.java.txt"));
        assertEqualsIgnoringLineEndings(expected, actual);
    }

    @Test
    void integer_필드는_Long으로_방출한다() throws Exception {
        // 계정과목별 금액(354106373903)처럼 int 범위를 넘는 값이 와도 역직렬화가 깨지지 않아야 한다
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertTrue(source.contains("private Long totalCount;"));
        assertTrue(!source.contains("private Integer totalCount;"));
    }

    @Test
    void JSON이면_XML_애노테이션_대신_JsonProperty를_쓴다() throws Exception {
        // ClientEmitter는 JSON일 때 ObjectMapper를 쓰는데, 평범한 ObjectMapper는
        // @JacksonXmlProperty를 읽지 못해 이름이 다른 필드가 조용히 매핑되지 않는다.
        IrSpec ir = jsonIr();
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertFalse(source.contains("JacksonXml"), "JSON DTO에 XML 애노테이션이 남으면 안 된다");
        assertFalse(source.contains("com.fasterxml.jackson.dataformat.xml"));
        assertTrue(source.contains("import com.fasterxml.jackson.annotation.JsonProperty;"));
        assertTrue(source.contains("@JsonProperty(\"item\")"));
    }

    @Test
    void JSON이면_루트_이름을_JsonRootName으로_남긴다() throws Exception {
        // XML은 루트 요소를 Jackson이 벗겨주지만 JSON은 {"response":{...}}처럼 키로 들어온다.
        // 이름을 안 남기면 바깥 키가 ignoreUnknown에 먹혀 전 필드가 null인 객체가 조용히 나온다.
        IrSpec ir = jsonIr();
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertTrue(source.contains("@JsonRootName(\"response\")"));
        assertTrue(source.contains("import com.fasterxml.jackson.annotation.JsonRootName;"));
    }

    @Test
    void XML이면_기존대로_XML_애노테이션을_쓴다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertTrue(source.contains("@JacksonXmlRootElement"));
        assertFalse(source.contains("import com.fasterxml.jackson.annotation.JsonProperty;"));
    }

    @Test
    void JSON이면_리스트_래퍼가_배열_형태도_받는다() throws Exception {
        // 실호출 확인: 에어코리아는 "items": [...] 로 배열을 바로 준다.
        // 반면 XML 구조를 그대로 옮긴 기관은 "items": {"item": [...]} 로 준다.
        // 문서의 응답 명세표에는 컨테이너 모양이 없어 둘 다 받아야 한다.
        IrSpec ir = jsonIr();
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertTrue(source.contains("@JsonCreator"));
        assertTrue(source.contains("import com.fasterxml.jackson.annotation.JsonCreator;"));
        assertTrue(source.contains("public static Items ofArray(List<Item> item)"));
    }

    @Test
    void XML이면_배열_수용_팩토리를_만들지_않는다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String source = new DtoEmitter().emit(ir, root);

        assertFalse(source.contains("@JsonCreator"));
    }

    private IrSpec jsonIr() throws Exception {
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        ApiSpec a = base.api();
        return new IrSpec(
                new ApiSpec(a.apiId(), a.apiName(), a.description(), a.baseUrl(), a.endpoint(),
                        a.httpMethod(), "JSON", a.requestParameters(), a.responseFields(), a.errorSpec()),
                base.generatorHints());
    }

    @Test
    void 서로_다른_경로의_클래스명이_겹치면_뒤_클래스에_부모_클래스명을_붙인다() {
        // 내부 노드는 모두 루트 클래스 바로 아래 형제 중첩 클래스가 되어 이름이 같으면 "already defined"로 컴파일되지 않는다
        String source = emit("SampleApi",
                field("response.header.items.b"),
                field("response.body.items.item[].x"));

        assertTrue(source.contains("public static class Items {"), source);
        assertTrue(source.contains("public static class BodyItems {"), source);
        // 필드 이름은 그대로라 두 곳 모두 items 키로 읽는다
        assertTrue(source.contains("private Items items;"), source);
        assertTrue(source.contains("private BodyItems items;"), source);
        assertTrue(source.contains("private List<Item> item;"), source);
    }

    @Test
    void 내부노드_클래스명이_루트클래스명과_겹치면_루트_요소_이름을_붙인다() {
        // 중첩 클래스는 감싸는 클래스와 이름이 같을 수 없다
        String source = emit("SampleApi", field("response.sampleApiResponse.a"));

        assertTrue(source.contains("public static class ResponseSampleApiResponse {"), source);
        assertTrue(source.contains("private ResponseSampleApiResponse sampleApiResponse;"), source);
    }

    @Test
    void 내부노드_클래스명이_예약된_타입이름과_겹치면_이름을_바꾼다() {
        // List라는 중첩 클래스가 있으면 List 필드 타입이 java.util.List 대신 그 클래스를 가리킨다
        String source = emit("SampleApi", field("response.list.a"), field("response.items.item[].b"));

        assertFalse(source.contains("public static class List {"), source);
        assertTrue(source.contains("public static class ResponseList {"), source);
        assertTrue(source.contains("private ResponseList list;"), source);
        assertTrue(source.contains("private List<Item> item;"), source);
    }

    @Test
    void 부모_클래스명을_붙여도_겹치면_번호를_붙인다() {
        // list는 ResponseList가 되어야 하는데 앞의 responseList가 그 이름을 이미 쓴다
        String source = emit("SampleApi", field("response.responseList.a"), field("response.list.b"));

        assertTrue(source.contains("public static class ResponseList {"), source);
        assertTrue(source.contains("public static class List2 {"), source);
        assertTrue(source.contains("private List2 list;"), source);
    }

    @Test
    void 루트_요소_이름을_붙인_이름이_식별자가_아니면_번호를_붙인다() {
        // 루트 요소 이름은 어노테이션에만 들어가 Java 이름으로 검사하지 않는다. 숫자로 시작하면 앞에 붙일 수 없다
        String source = emit("SampleApi", field("1st.list.a"));

        assertTrue(source.contains("public static class List2 {"), source);
    }

    @Test
    void 대소문자만_다른_클래스명도_겹침으로_본다() {
        // Items와 ITEMS는 컴파일되지만 대소문자를 가리지 않는 파일 시스템에서 class 파일 하나를 함께 쓴다
        String source = emit("SampleApi", field("response.header.items.a"), field("response.body.ITEMS.b"));

        assertTrue(source.contains("public static class Items {"), source);
        assertTrue(source.contains("public static class BodyITEMS {"), source);
    }

    @Test
    void javac가_무시하는_글자만_다른_클래스명도_겹침으로_본다() {
        // javac는 soft hyphen(U+00AD) 같은 무시 가능 문자를 빼고 읽어 두 이름을 같은 클래스로 본다
        String source = emit("SampleApi", field("response.header.items.a"), field("response.body.ite\u00ADms.b"));

        assertTrue(source.contains("public static class Items {"), source);
        assertTrue(source.contains("public static class BodyIte\u00ADms {"), source);
    }

    private String emit(String apiId, ResponseField... fields) {
        List<ResponseField> fieldList = List.of(fields);
        return new DtoEmitter().emit(irWithApiId(apiId, fieldList), new DtoTreeBuilder().build(fieldList));
    }

    private static ResponseField field(String path) {
        return new ResponseField(path, "string", null, null);
    }

    private IrSpec irWithApiId(String apiId, List<ResponseField> fields) {
        ApiSpec api = new ApiSpec(
                apiId, "샘플 API", "설명", "http://example.com", "/sample", "GET", "XML",
                List.of(), fields, new ErrorSpec("00"));
        GeneratorHints hints = new GeneratorHints("kr.go.h2spec.client.sample");
        return new IrSpec(api, hints);
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n");
    }
}
