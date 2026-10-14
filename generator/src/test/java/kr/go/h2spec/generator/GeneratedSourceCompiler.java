package kr.go.h2spec.generator;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 생성된 소스를 javac로 컴파일해 보는 테스트 유틸리티. CLI는 생성 코드를 컴파일하지 않으므로 컴파일되는지는 테스트가 본다.
 * classpath는 테스트 classpath다(generator/build.gradle이 interceptor-core·jackson-dataformat-xml·spring-web을 넣어 둔다).
 */
public final class GeneratedSourceCompiler {

    private GeneratedSourceCompiler() {
    }

    /** written 중 .java를 classesDir로 컴파일한다. 실패하면 javac 진단을 담아 실패시키고, 성공하면 만들어진 클래스 파일 이름을 돌려준다 */
    public static List<String> assertCompiles(List<Path> written, Path classesDir) throws IOException {
        Files.createDirectories(classesDir);
        List<String> args = new ArrayList<>(List.of(
                "-encoding", "UTF-8",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString()));
        written.stream()
                .map(Path::toString)
                .filter(p -> p.endsWith(".java"))
                .forEach(args::add);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();

        int result = compiler.run(null, null, diagnostics, args.toArray(String[]::new));

        assertEquals(0, result, "생성된 소스 컴파일 실패:\n" + diagnostics.toString(StandardCharsets.UTF_8));
        try (Stream<Path> files = Files.walk(classesDir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".class"))
                    .sorted()
                    .toList();
        }
    }

    /**
     * javap -p -constants 출력. 클래스를 초기화하지 않고 static 초기화 블록이 있는지 보기 위해 쓴다.
     * 끼워 넣은 static 블록이 살아 있으면 클래스를 불러 초기화하는 순간 그 코드가 실행된다.
     */
    public static String javap(Path classesDir, String className) {
        java.util.spi.ToolProvider javap = java.util.spi.ToolProvider.findFirst("javap").orElseThrow();
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);

        int result = javap.run(writer, writer, "-p", "-constants", "-cp", classesDir.toString(), className);

        writer.flush();
        assertEquals(0, result, out.toString());
        return out.toString();
    }
}
