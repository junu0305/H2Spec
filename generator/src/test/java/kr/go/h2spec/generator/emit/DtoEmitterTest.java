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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoEmitterTest {

    @Test
    void 골든파일과_일치한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String actual = new DtoEmitter().emit(ir, root);

        String expected = Files.readString(resource("/golden/RTMSDataSvcAptTradeDevResponse.java.txt"));
        assertEquals(expected, actual);
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
}
