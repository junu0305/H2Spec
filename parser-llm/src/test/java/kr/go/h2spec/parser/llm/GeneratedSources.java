package kr.go.h2spec.parser.llm;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 생성된 소스를 javac로 컴파일해 본다. CLI는 생성 코드를 컴파일하지 않으므로 숫자로 시작하는 메서드 이름 같은 오류는 컴파일해야 드러난다.
 * generator 테스트의 GeneratedSourceCompiler와 같은 방법이다. 그 클래스는 generator의 테스트 소스라 여기서 쓸 수 없어 같은 일을 하는 것을 둔다.
 * classpath는 테스트 classpath다(parser-llm/build.gradle이 interceptor-core·jackson-dataformat-xml·spring-web을 넣어 둔다).
 */
final class GeneratedSources {

    private GeneratedSources() {
    }

    /** written 중 .java를 classesDir로 컴파일한다. 실패하면 javac 진단을 담아 실패시킨다 */
    static void assertCompiles(List<Path> written, Path classesDir) throws IOException {
        Files.createDirectories(classesDir);
        List<String> args = new ArrayList<>(List.of(
                "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString()));
        written.stream()
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .forEach(args::add);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javac가 없습니다. JDK로 테스트를 돌려야 합니다");

        int result = compiler.run(null, null, diagnostics, args.toArray(String[]::new));

        assertEquals(0, result, "생성된 소스 컴파일 실패:\n" + diagnostics.toString(StandardCharsets.UTF_8));
    }
}
