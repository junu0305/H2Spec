package kr.go.h2spec.cli;

import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.parser.DocxSpecParser;
import kr.go.h2spec.parser.HwpSpecParser;
import kr.go.h2spec.parser.ParsedApi;
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

    /** HWPX(신형식)는 미지원 — hwplib은 구형 바이너리(.hwp)만 다룬다 */
    private static final List<String> UNSUPPORTED_EXTENSIONS = List.of(".hwpx");
    private static final String DOCX_EXTENSION = ".docx";
    private static final String HWP_EXTENSION = ".hwp";

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, required = true, description = "입력 파일 (HWP/DOCX 명세서 또는 IR JSON)")
    private Path input;

    @Option(names = {"-o", "--output"}, description = "출력 디렉터리 (기본: ./generated)")
    private Path output = Path.of("generated");

    @Option(names = "--success-code", description = "정상 처리로 간주할 resultCode (IR의 errorSpec 값을 덮어씀)")
    private String successCode;

    @Override
    public Integer call() {
        String fileName = input.getFileName().toString().toLowerCase(Locale.ROOT);
        if (UNSUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            err("HWPX 명세서 파싱은 아직 지원되지 않습니다. HWP/DOCX로 저장하거나 IR JSON을 사용해 주세요.");
            return 1;
        }
        if (!Files.exists(input)) {
            err("입력 파일이 없습니다: " + input);
            return 1;
        }
        try {
            if (fileName.endsWith(DOCX_EXTENSION)) {
                convertDocument(new DocxSpecParser().parse(input));
            } else if (fileName.endsWith(HWP_EXTENSION)) {
                convertDocument(new HwpSpecParser().parse(input));
            } else {
                generateFrom(input);
            }
            return 0;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            err(e.getMessage());
            return 1;
        } catch (IOException e) {
            err("변환 실패: " + e.getMessage());
            return 1;
        }
    }

    /** 문서에서 추출한 오퍼레이션별 IR JSON을 저장한 뒤 각각 코드 생성 */
    private void convertDocument(List<ParsedApi> apis) throws IOException {
        Path irDir = Files.createDirectories(output.resolve("ir"));
        for (ParsedApi parsed : apis) {
            Path irFile = irDir.resolve(parsed.apiId() + ".json");
            Files.writeString(irFile, parsed.ir().toPrettyString());
            spec.commandLine().getOut().println("IR 추출: " + irFile);
            generateFrom(irFile);
        }
    }

    private void generateFrom(Path irJson) throws IOException {
        IrSpec ir = new IrLoader().load(irJson);
        if (successCode != null) {
            ir = withSuccessCode(ir, successCode);
        }
        List<Path> written = new CodeGenerator().generate(ir, output);
        written.forEach(path -> spec.commandLine().getOut().println("생성: " + path));
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
