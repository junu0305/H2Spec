package kr.go.h2spec.cli;

import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.parser.DocxSpecParser;
import kr.go.h2spec.parser.ParsedApi;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.Callable;

/** 명세 파일을 OpenAPI/클라이언트/DTO 산출물로 변환하는 서브커맨드. */
@Command(
        name = "convert",
        description = "명세 파일을 OpenAPI 스펙과 Spring 클라이언트 코드로 변환",
        mixinStandardHelpOptions = true)
public class ConvertCommand implements Callable<Integer> {

    /** HWP 파싱은 미지원 (이슈 #1은 DOCX 우선) */
    private static final List<String> UNSUPPORTED_EXTENSIONS = List.of(".hwp", ".hwpx");
    private static final String DOCX_EXTENSION = ".docx";
    private static final String JSON_EXTENSION = ".json";
    private static final String XML_FORMAT = "XML";
    private static final String JSON_FORMAT = "JSON";

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, required = true,
            description = "입력 파일(DOCX 명세서 또는 IR JSON) 또는 명세 파일들이 담긴 디렉터리")
    private Path input;

    @Option(names = {"-o", "--output"}, description = "출력 디렉터리 (기본: ./generated)")
    private Path output = Path.of("generated");

    /** 배치에서 이미 산출물을 낸 apiId. 뒤 문서가 앞 문서를 덮어쓰는 것을 알리기 위해 추적한다 */
    private final Map<String, Path> writtenApiIds = new LinkedHashMap<>();

    @Option(names = "--success-code", description = "정상 처리로 간주할 resultCode (IR의 errorSpec 값을 덮어씀)")
    private String successCode;

    @Option(names = "--format", description = "응답 포맷 xml 또는 json (문서에서 판별한 값을 덮어씀)")
    private String format;

    @Option(names = "--package", description = "생성 코드의 기준 패키지 (IR의 generatorHints.targetPackage를 덮어씀). "
            + "오퍼레이션별로 apiId를 소문자화한 하위 패키지가 자동으로 붙는다 (예: com.example.publicdata → com.example.publicdata.msrstnlist)")
    private String targetPackage;

    @Override
    public Integer call() {
        if (!Files.exists(input)) {
            err("입력 파일이 없습니다: " + input);
            return 1;
        }
        if (format != null && !XML_FORMAT.equalsIgnoreCase(format) && !JSON_FORMAT.equalsIgnoreCase(format)) {
            err("--format 값은 xml 또는 json 이어야 합니다: " + format);
            return 1;
        }
        return Files.isDirectory(input) ? convertDirectory() : (convertFile(input) ? 0 : 1);
    }

    /** 디렉터리 안의 모든 명세 파일(DOCX, IR JSON)을 찾아 각각 변환한다. */
    private int convertDirectory() {
        List<Path> targets;
        try (var files = Files.list(input)) {
            targets = files
                    .filter(Files::isRegularFile)
                    .filter(this::isSupportedSpecFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            err("디렉터리를 읽을 수 없습니다: " + e.getMessage());
            return 1;
        }
        if (targets.isEmpty()) {
            err(hasUnsupportedSpecFile()
                    ? "HWP 명세서 파싱은 아직 지원되지 않습니다. DOCX로 변환하거나 IR JSON을 사용해 주세요: " + input
                    : "디렉터리에서 변환할 명세 파일(.docx, .json)을 찾지 못했습니다: " + input);
            return 1;
        }

        int failureCount = 0;
        for (Path target : targets) {
            spec.commandLine().getOut().println("=== " + input.relativize(target) + " ===");
            if (!convertFile(target)) {
                failureCount++;
            }
        }
        spec.commandLine().getOut().println(
                String.format("%d개 중 %d개 변환 성공, %d개 실패", targets.size(), targets.size() - failureCount, failureCount));
        return failureCount == 0 ? 0 : 1;
    }

    /** 지원 파일이 하나도 없을 때, 원인이 HWP뿐인지 알려 안내 문구를 고르기 위해 확인한다. */
    private boolean hasUnsupportedSpecFile() {
        try (var files = Files.list(input)) {
            return files.filter(Files::isRegularFile).anyMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                return UNSUPPORTED_EXTENSIONS.stream().anyMatch(name::endsWith);
            });
        } catch (IOException e) {
            return false;
        }
    }

    private boolean isSupportedSpecFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(DOCX_EXTENSION) || fileName.endsWith(JSON_EXTENSION);
    }

    /** 파일 하나(DOCX 또는 IR JSON)를 변환한다. 실패해도 배치 전체를 중단하지 않도록 boolean으로 결과를 알린다. */
    private boolean convertFile(Path file) {
        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (UNSUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            err("HWP 명세서 파싱은 아직 지원되지 않습니다. DOCX로 변환하거나 IR JSON을 사용해 주세요. (" + file + ")");
            return false;
        }
        try {
            if (fileName.endsWith(DOCX_EXTENSION)) {
                convertDocx(file);
            } else {
                generateFrom(file, file);
            }
            return true;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            err(e.getMessage());
            return false;
        } catch (IOException e) {
            err("변환 실패 (" + file + "): " + e.getMessage());
            return false;
        }
    }

    /** DOCX → 오퍼레이션별 IR JSON 추출 후 각각 코드 생성 */
    private void convertDocx(Path docxFile) throws IOException {
        List<ParsedApi> apis = new DocxSpecParser().parse(docxFile);
        Path irDir = Files.createDirectories(output.resolve("ir"));
        for (ParsedApi parsed : apis) {
            Path irFile = irDir.resolve(parsed.apiId() + ".json");
            Files.writeString(irFile, parsed.ir().toPrettyString());
            spec.commandLine().getOut().println("IR 추출: " + irFile);
            generateFrom(irFile, docxFile);
        }
    }

    private void generateFrom(Path irJson, Path source) throws IOException {
        IrSpec ir = new IrLoader().load(irJson);
        warnOnApiIdOverwrite(ir.api().apiId(), source);
        if (successCode != null) {
            ir = withSuccessCode(ir, successCode);
        }
        if (format != null) {
            ir = withResponseFormat(ir, format.toUpperCase(Locale.ROOT));
        }
        if (targetPackage != null) {
            ir = withTargetPackage(ir, targetPackage);
        }
        List<Path> written = new CodeGenerator().generate(ir, output);
        written.forEach(path -> spec.commandLine().getOut().println("생성: " + path));
    }

    /**
     * 배치는 모든 문서를 한 출력 디렉터리에 쏟으므로 apiId가 겹치면 앞선 산출물이 조용히 사라진다.
     * 성공으로 집계되기 때문에 알리지 않으면 알아채기 어렵다.
     */
    private void warnOnApiIdOverwrite(String apiId, Path source) {
        Path previous = writtenApiIds.put(apiId, source);
        if (previous != null) {
            err("apiId가 겹쳐 앞선 산출물을 덮어씁니다: " + apiId
                    + " (" + previous.getFileName() + " → " + source.getFileName() + ")");
        }
    }

    private IrSpec withResponseFormat(IrSpec ir, String responseFormat) {
        ApiSpec api = ir.api();
        return new IrSpec(
                new ApiSpec(api.apiId(), api.apiName(), api.description(), api.baseUrl(),
                        api.endpoint(), api.httpMethod(), responseFormat,
                        api.requestParameters(), api.responseFields(), api.errorSpec()),
                ir.generatorHints());
    }

    private IrSpec withSuccessCode(IrSpec ir, String code) {
        ApiSpec api = ir.api();
        return new IrSpec(
                new ApiSpec(api.apiId(), api.apiName(), api.description(), api.baseUrl(),
                        api.endpoint(), api.httpMethod(), api.responseFormat(),
                        api.requestParameters(), api.responseFields(), new ErrorSpec(code)),
                ir.generatorHints());
    }

    private IrSpec withTargetPackage(IrSpec ir, String basePackage) {
        String subPackage = ir.api().apiId().toLowerCase(Locale.ROOT);
        return new IrSpec(ir.api(), new GeneratorHints(basePackage + "." + subPackage));
    }

    private void err(String message) {
        spec.commandLine().getErr().println(message);
    }
}
