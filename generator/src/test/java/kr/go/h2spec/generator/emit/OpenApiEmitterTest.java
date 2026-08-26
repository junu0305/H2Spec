package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.model.DtoNode;
import kr.go.h2spec.generator.model.DtoTreeBuilder;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static kr.go.h2spec.generator.GoldenFileAssertions.assertEqualsIgnoringLineEndings;

class OpenApiEmitterTest {

    @Test
    void 골든파일과_일치한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String actual = new OpenApiEmitter().emit(ir, root);

        // 골든과 불일치 시 diff 확인용 덤프
        Files.writeString(Path.of("build/openapi-actual.json"), actual);

        String expected = Files.readString(resource("/golden/RTMSDataSvcAptTradeDevOpenApi.json"));
        assertEqualsIgnoringLineEndings(expected, actual);
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }
}
