package kr.go.h2spec.generator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeGeneratorTest {

    @TempDir
    Path tempDir;

    @Test
    void 패키지_경로에_DTO와_클라이언트와_OpenAPI를_생성한다() throws Exception {
        List<Path> written = new CodeGenerator().generate(irPath(), tempDir);

        assertEquals(3, written.size());
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/dto/RTMSDataSvcAptTradeDevResponse.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "kr/go/h2spec/client/landmolit/RTMSDataSvcAptTradeDevClient.java")));
        assertTrue(Files.exists(tempDir.resolve(
                "openapi/RTMSDataSvcAptTradeDev.json")));
    }

    @Test
    void 생성된_소스가_실제로_컴파일된다() throws Exception {
        List<Path> written = new CodeGenerator().generate(irPath(), tempDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = Files.createDirectories(tempDir.resolve("classes"));
        List<String> args = new ArrayList<>(List.of(
                "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString()));
        // 컴파일 대상은 .java만 (OpenAPI JSON은 제외)
        written.stream()
                .map(Path::toString)
                .filter(p -> p.endsWith(".java"))
                .forEach(args::add);

        int result = compiler.run(null, null, null, args.toArray(String[]::new));

        assertEquals(0, result, "생성된 소스 컴파일 실패");
    }

    private Path irPath() throws Exception {
        return Path.of(getClass().getResource("/ir/schema-example.json").toURI());
    }
}
