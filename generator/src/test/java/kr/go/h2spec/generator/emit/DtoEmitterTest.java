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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void 서로_다른_경로의_클래스명이_겹치면_예외를_던진다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.header.items.b", "string", null, null),
                new ResponseField("response.body.items.item[].x", "string", null, null));
        DtoNode root = new DtoTreeBuilder().build(fields);
        IrSpec ir = irWithApiId("SampleApi", fields);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoEmitter().emit(ir, root));

        assertTrue(e.getMessage().contains("충돌"));
        assertTrue(e.getMessage().contains("Items"));
        assertTrue(e.getMessage().contains("response.header.items"));
        assertTrue(e.getMessage().contains("response.body.items"));
    }

    @Test
    void 내부노드_클래스명이_루트클래스명과_겹치면_예외를_던진다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.sampleApiResponse.a", "string", null, null));
        DtoNode root = new DtoTreeBuilder().build(fields);
        IrSpec ir = irWithApiId("SampleApi", fields);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoEmitter().emit(ir, root));

        assertTrue(e.getMessage().contains("충돌"));
        assertTrue(e.getMessage().contains("SampleApiResponse"));
    }

    @Test
    void 내부노드_클래스명이_예약된_타입이름과_겹치면_예외를_던진다() {
        List<ResponseField> fields = List.of(
                new ResponseField("response.list.a", "string", null, null));
        DtoNode root = new DtoTreeBuilder().build(fields);
        IrSpec ir = irWithApiId("SampleApi", fields);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new DtoEmitter().emit(ir, root));

        assertTrue(e.getMessage().contains("충돌"));
        assertTrue(e.getMessage().contains("List"));
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
