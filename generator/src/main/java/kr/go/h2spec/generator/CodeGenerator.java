package kr.go.h2spec.generator;

import kr.go.h2spec.generator.emit.ClientEmitter;
import kr.go.h2spec.generator.emit.DtoEmitter;
import kr.go.h2spec.generator.emit.JavaNames;
import kr.go.h2spec.generator.emit.OpenApiEmitter;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.model.DtoNode;
import kr.go.h2spec.generator.model.DtoTreeBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** IR JSON → DTO/클라이언트 소스 파일 생성 퍼사드. */
public class CodeGenerator {

    public List<Path> generate(Path irJson, Path outputDir) throws IOException {
        return generate(new IrLoader().load(irJson), outputDir);
    }

    public List<Path> generate(IrSpec ir, Path outputDir) throws IOException {
        DtoNode root = new DtoTreeBuilder().build(ir.api().responseFields());

        String pkgPath = ir.generatorHints().targetPackage().replace('.', '/');
        String apiClass = JavaNames.pascal(ir.api().apiId());

        Path dtoFile = outputDir.resolve(pkgPath).resolve("dto").resolve(apiClass + "Response.java");
        Path clientFile = outputDir.resolve(pkgPath).resolve(apiClass + "Client.java");
        Path openApiFile = outputDir.resolve("openapi").resolve(ir.api().apiId() + ".json");

        Files.createDirectories(dtoFile.getParent());
        Files.createDirectories(openApiFile.getParent());
        Files.writeString(dtoFile, new DtoEmitter().emit(ir, root));
        Files.writeString(clientFile, new ClientEmitter().emit(ir));
        Files.writeString(openApiFile, new OpenApiEmitter().emit(ir, root));
        return List.of(dtoFile, clientFile, openApiFile);
    }
}
