package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * IR이 generator를 끝까지 통과하는지 본다: IrLoader → CodeGenerator → 생성 코드 javac 컴파일 (generator 통과율).
 * CLI는 생성 코드를 컴파일하지 않으므로 숫자로 시작하는 메서드 이름 같은 오류는 컴파일해야 드러난다.
 * <p>
 * 컴파일 classpath는 이 프로그램의 classpath다. build.gradle이 생성 코드가 쓰는 interceptor-core·spring-web·
 * jackson-dataformat-xml을 넣어 둔다. generator 테스트의 GeneratedSourceCompiler와 같은 방법이다.
 */
final class GeneratorCheck {

    /** javac 진단에서 리포트에 옮길 최대 줄 수. 한 오류가 수십 줄의 연쇄 오류를 낳는다 */
    private static final int MAX_DIAGNOSTIC_LINES = 5;

    private final JavaCompiler compiler;
    private final String classpath;

    /**
     * 이 프로그램의 classpath로 컴파일하는 확인기를 만든다.
     *
     * @throws IllegalStateException JDK가 아닌 JRE로 실행해 javac가 없을 때
     */
    GeneratorCheck() {
        this(System.getProperty("java.class.path"));
    }

    /**
     * @param classpath 생성 코드를 컴파일할 classpath
     * @throws IllegalStateException JDK가 아닌 JRE로 실행해 javac가 없을 때
     */
    GeneratorCheck(String classpath) {
        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("javac를 찾지 못했습니다. generator 통과율은 생성 코드를 컴파일해야 셀 수 있어 JDK로 실행해야 합니다");
        }
        this.classpath = classpath;
    }

    /**
     * 실패한 단계.
     */
    enum Stage {
        /** IR 필수 필드·v1 범위 검사 */
        IR_LOADER("IrLoader"),
        /** 이름 검사와 코드 생성 (serviceKey가 없는 IR 등) */
        CODE_GENERATOR("CodeGenerator"),
        /** 생성 코드 컴파일 */
        JAVAC("javac");

        private final String label;

        Stage(String label) {
            this.label = label;
        }

        /** @return 리포트에 쓰는 이름 */
        String label() {
            return label;
        }
    }

    /**
     * 확인 결과.
     *
     * @param passed  javac 컴파일까지 통과했는지
     * @param stage   실패한 단계. 통과했으면 null
     * @param message 실패 이유. 통과했으면 null
     */
    record Outcome(boolean passed, Stage stage, String message) {

        static final Outcome PASSED = new Outcome(true, null, null);

        static Outcome failed(Stage stage, String message) {
            return new Outcome(false, stage, message);
        }
    }

    /**
     * IR 하나를 확인한다. 실패해도 예외를 던지지 않고 실패한 단계와 이유를 돌려준다.
     *
     * @param ir      확인할 IR
     * @param workDir IR·생성 코드·클래스 파일을 둘 빈 디렉터리
     * @return 확인 결과
     * @throws IOException workDir에 쓰지 못했을 때
     */
    Outcome check(ObjectNode ir, Path workDir) throws IOException {
        Files.createDirectories(workDir);
        Path irFile = workDir.resolve("ir.json");
        Files.writeString(irFile, ir.toPrettyString());

        IrSpec spec;
        try {
            spec = new IrLoader().load(irFile);
        } catch (IOException | RuntimeException e) {
            return Outcome.failed(Stage.IR_LOADER, e.getMessage());
        }
        List<Path> written;
        try {
            written = new CodeGenerator().generate(spec, workDir.resolve("src"));
        } catch (IOException | RuntimeException e) {
            return Outcome.failed(Stage.CODE_GENERATOR, e.getMessage());
        }
        String diagnostics = compile(written, workDir.resolve("classes"));
        return diagnostics == null ? Outcome.PASSED : Outcome.failed(Stage.JAVAC, diagnostics);
    }

    /** 컴파일하고, 실패하면 진단 앞부분을 돌려준다. 성공하면 null */
    private String compile(List<Path> written, Path classesDir) throws IOException {
        Files.createDirectories(classesDir);
        // 애너테이션 처리기 탐색과 경고는 통과 여부와 무관하고 시간만 든다
        List<String> args = new ArrayList<>(List.of(
                "-encoding", "UTF-8",
                "-proc:none",
                "-nowarn",
                "-classpath", classpath,
                "-d", classesDir.toString()));
        written.stream()
                .map(Path::toString)
                .filter(path -> path.endsWith(".java"))
                .forEach(args::add);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int result = compiler.run(null, output, output, args.toArray(String[]::new));

        if (result == 0) {
            return null;
        }
        List<String> lines = output.toString(StandardCharsets.UTF_8).lines()
                .filter(line -> !line.isBlank())
                .limit(MAX_DIAGNOSTIC_LINES)
                .toList();
        return lines.isEmpty() ? "javac 종료 코드 " + result : String.join(" / ", lines);
    }
}
