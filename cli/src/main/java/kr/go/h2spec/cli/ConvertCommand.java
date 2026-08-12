package kr.go.h2spec.cli;

import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/** 명세 파일을 OpenAPI/클라이언트/DTO 산출물로 변환하는 서브커맨드. */
@Command(
        name = "convert",
        description = "명세 파일을 OpenAPI 스펙과 Spring 클라이언트 코드로 변환",
        mixinStandardHelpOptions = true)
public class ConvertCommand implements Callable<Integer> {

    /** parser 모듈(이슈 #1) 구현 후 지원될 명세서 문서 확장자 */
    private static final List<String> DOCUMENT_EXTENSIONS = List.of(".hwp", ".hwpx", ".docx");

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, required = true, description = "입력 파일 (현재 IR JSON만 지원)")
    private Path input;

    @Option(names = {"-o", "--output"}, description = "출력 디렉터리 (기본: ./generated)")
    private Path output = Path.of("generated");

    @Option(names = "--success-code", description = "정상 처리로 간주할 resultCode (IR의 errorSpec 값을 덮어씀)")
    private String successCode;

    @Override
    public Integer call() {
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (DOCUMENT_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            err("HWP/DOCX 명세서 파싱은 parser 모듈 구현 후 지원됩니다 (이슈 #1). 현재는 IR JSON만 지원합니다.");
            return 1;
        }
        if (!Files.exists(input)) {
            err("입력 파일이 없습니다: " + input);
            return 1;
        }
        try {
            IrSpec ir = new IrLoader().load(input);
            if (successCode != null) {
                ir = withSuccessCode(ir, successCode);
            }
            List<Path> written = new CodeGenerator().generate(ir, output);
            written.forEach(path -> spec.commandLine().getOut().println("생성: " + path));
            return 0;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            err(e.getMessage());
            return 1;
        } catch (IOException e) {
            err("변환 실패: " + e.getMessage());
            return 1;
        }
    }

    private IrSpec withSuccessCode(IrSpec ir, String code) {
        ApiSpec api = ir.api();
        return new IrSpec(
                new ApiSpec(api.apiId(), api.apiName(), api.description(), api.baseUrl(),
                        api.endpoint(), api.httpMethod(), api.responseFormat(),
                        api.requestParameters(), api.responseFields(), new ErrorSpec(code)),
                ir.generatorHints());
    }

    private void err(String message) {
        spec.commandLine().getErr().println(message);
    }
}
