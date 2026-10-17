package kr.go.h2spec.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.CodeGenerator;
import kr.go.h2spec.generator.emit.AuthKey;
import kr.go.h2spec.generator.emit.JavaNameRules;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.parser.DocumentReaders;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.RuleSpecParser;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.SpecParseResult.TokenUsage;
import kr.go.h2spec.parser.SpecParser;
import kr.go.h2spec.parser.llm.ExtractionCache;
import kr.go.h2spec.parser.llm.LlmSpecParser;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmProviders;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.client.ProviderResolver;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/** 명세 파일을 OpenAPI/클라이언트/DTO 산출물로 변환하는 서브커맨드. */
@Command(
        name = "convert",
        description = "명세 파일(DOCX/HWP/HWPX/IR JSON)을 OpenAPI 스펙과 Spring 클라이언트 코드로 변환",
        mixinStandardHelpOptions = true)
public class ConvertCommand implements Callable<Integer> {

    private static final String JSON_EXTENSION = ".json";
    private static final String XML_FORMAT = "XML";
    private static final String JSON_FORMAT = "JSON";
    /** 덮어쓰기 옵션으로 바꾼 값을 IR의 reviewNotes에 남길 때 붙이는 머리말 */
    private static final String CLI_NOTE_PREFIX = "[CLI] ";
    /** 기본 엔진. 범용 API 키가 환경에 있다는 것만으로 문서를 외부로 보내고 과금하지 않도록 rule이다 */
    private static final String RULE_ENGINE = "rule";
    private static final String LLM_ENGINE = "llm";
    /** h2spec 전용 설정이 있을 때만 llm을 쓰는 엔진. 실패한 문서를 rule로 대신 변환하는 경로와 함께 앞으로 들어온다 */
    private static final String AUTO_ENGINE = "auto";
    /** --model이 없을 때 읽는 모델 환경변수. 회사는 ProviderResolver가 H2SPEC_LLM_PROVIDER로 정한다 */
    private static final String MODEL_ENVIRONMENT_VARIABLE = "H2SPEC_LLM_MODEL";
    /** --effort가 없을 때 읽는 추론 깊이 환경변수 */
    private static final String EFFORT_ENVIRONMENT_VARIABLE = "H2SPEC_LLM_EFFORT";

    /** 환경변수. llm 엔진으로 변환할 문서가 있을 때만 읽는다. rule 엔진은 회사를 정하지 않고 키 환경변수도 읽지 않는다 */
    private final Supplier<Map<String, String>> environment;
    /** llm 엔진에 쓸 수 있는 회사 */
    private final List<LlmProviderFactory> providers;
    /** 정한 회사와 설정으로 llm 엔진을 만든다 */
    private final LlmParserFactory llmParsers;

    /** 명세 문서를 IR로 바꾸는 엔진. 기본은 rule 엔진이고, --engine llm이면 파일을 변환하기 전에 llm 엔진으로 바꾼다 */
    private SpecParser specParser = new RuleSpecParser();
    /** llm 엔진으로 변환한 문서의 토큰 사용량 합계. 이번 실행에서 llm 엔진을 쓰지 않으면 null이다 */
    private TokenUsage llmTokens;

    @Spec
    private CommandSpec spec;

    @Option(names = {"-i", "--input"}, required = true,
            description = "입력 파일(DOCX/HWP/HWPX 명세서 또는 IR JSON) 또는 명세 파일들이 담긴 디렉터리")
    private Path input;

    @Option(names = {"-o", "--output"}, description = "출력 디렉터리 (기본: ./generated)")
    private Path output = Path.of("generated");

    /** 이번 실행에서 이미 IR이나 코드를 낸 apiId. 뒤 문서가 앞 문서의 산출물을 덮어쓰는 것을 알리기 위해 추적한다 */
    private final Map<String, Path> writtenApiIds = new LinkedHashMap<>();

    @Option(names = "--success-code", description = "정상 처리로 간주할 resultCode (IR의 errorSpec 값을 덮어씀)")
    private String successCode;

    @Option(names = "--format", description = "응답 포맷 xml 또는 json (문서에서 판별한 값을 덮어씀)")
    private String format;

    @Option(names = "--package", description = "생성 코드의 기준 패키지 (IR의 generatorHints.targetPackage를 덮어씀). "
            + "오퍼레이션별로 apiId를 소문자화한 하위 패키지가 자동으로 붙는다 (예: com.example.publicdata → com.example.publicdata.msrstnlist)")
    private String targetPackage;

    @Option(names = "--engine", description = "명세 문서를 해석할 엔진 rule 또는 llm (기본: rule). "
            + "llm은 문서 내용을 LLM 회사로 보내며 그 회사의 API 키를 환경변수로 받는다. IR JSON 입력은 엔진과 무관하게 바로 생성한다")
    private String engine = RULE_ENGINE;

    @Option(names = "--provider", completionCandidates = ProviderIds.class,
            description = "llm 엔진에 쓸 LLM 회사 (${COMPLETION-CANDIDATES}). 없으면 H2SPEC_LLM_PROVIDER, "
                    + "그것도 없으면 키 환경변수가 있는 회사가 하나일 때 그 회사")
    private String provider;

    @Option(names = "--model", description = "llm 엔진의 모델 ID (기본: H2SPEC_LLM_MODEL, 없으면 회사별 기본 모델)")
    private String model;

    @Option(names = "--effort", description = "llm 엔진의 추론 깊이 (기본: H2SPEC_LLM_EFFORT, 없으면 회사별 기본값. "
            + "기본은 medium. claude는 low~max, openai는 none~max, gemini는 low~high). 높을수록 출력 토큰(비용)과 시간이 늘어난다")
    private String effort;

    @Option(names = "--no-cross-check", description = "llm 엔진의 결과를 rule 엔진의 결과와 대조하지 않는다")
    private boolean noCrossCheck;

    @Option(names = "--no-cache", description = "llm 엔진의 결과 캐시를 쓰지 않는다. 같은 문서도 LLM을 다시 부른다")
    private boolean noCache;

    @Option(names = "--cache-dir", description = "llm 엔진의 결과 캐시 디렉터리 "
            + "(기본: XDG_CACHE_HOME 또는 ~/.cache 아래 h2spec/llm, Windows는 LOCALAPPDATA 아래 h2spec\\llm)")
    private Path cacheDir;

    /** 실제 환경변수와 회사 목록을 쓴다. picocli가 서브커맨드를 만들 때 부른다 */
    public ConvertCommand() {
        this(System::getenv, LlmProviders.ALL, LlmSpecParser::of);
    }

    /**
     * 테스트가 실제 환경변수·키·네트워크 없이 가짜 환경과 가짜 엔진으로 바꿀 때 쓴다.
     *
     * @param environment 환경변수를 읽는 곳. llm 엔진으로 변환할 문서가 있을 때만 부른다
     * @param providers   llm 엔진에 쓸 수 있는 회사
     * @param llmParsers  정한 회사와 설정으로 llm 엔진을 만드는 곳
     */
    ConvertCommand(Supplier<Map<String, String>> environment, List<LlmProviderFactory> providers,
                   LlmParserFactory llmParsers) {
        this.environment = Objects.requireNonNull(environment, "environment");
        this.providers = List.copyOf(providers);
        this.llmParsers = Objects.requireNonNull(llmParsers, "llmParsers");
    }

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
        if (AUTO_ENGINE.equalsIgnoreCase(engine)) {
            // 실패한 문서를 rule로 대신 변환하는 경로가 아직 없어, 받아 두면 rule이나 llm 한쪽으로 사용자가 모르게 바뀐다
            err("--engine auto는 아직 지원하지 않습니다. rule 또는 llm을 쓰세요");
            return 1;
        }
        if (!RULE_ENGINE.equalsIgnoreCase(engine) && !LLM_ENGINE.equalsIgnoreCase(engine)) {
            err("--engine 값은 rule 또는 llm 이어야 합니다: " + engine);
            return 1;
        }
        // 오퍼레이션마다 apiId 마디가 더 붙지만, 기준 패키지가 틀리면 모든 오퍼레이션의 생성이 실패하므로 먼저 알린다
        if (targetPackage != null && !JavaNameRules.isValidPackageName(targetPackage)) {
            err("--package 값이 Java 패키지 이름이 아닙니다: " + targetPackage);
            return 1;
        }
        if (RULE_ENGINE.equalsIgnoreCase(engine)) {
            warnUnusedLlmOptions();
        }
        return Files.isDirectory(input) ? convertDirectory() : convertSingleFile();
    }

    /** rule 엔진에는 쓰이지 않는 llm 엔진 옵션을 주었으면 알린다. --engine llm을 빠뜨린 것을 모른 채 rule 결과를 받지 않게 한다 */
    private void warnUnusedLlmOptions() {
        List<String> given = new ArrayList<>();
        if (provider != null) {
            given.add("--provider");
        }
        if (model != null) {
            given.add("--model");
        }
        if (effort != null) {
            given.add("--effort");
        }
        if (noCrossCheck) {
            given.add("--no-cross-check");
        }
        if (noCache) {
            given.add("--no-cache");
        }
        if (cacheDir != null) {
            given.add("--cache-dir");
        }
        if (!given.isEmpty()) {
            err("--engine llm에서만 쓰는 옵션을 무시하고 rule 엔진으로 변환합니다: " + String.join(", ", given));
        }
    }

    /** 파일 하나를 변환한다. 디렉터리와 같은 종료 코드 규칙을 따른다 */
    private int convertSingleFile() {
        if (!prepareEngine(List.of(input))) {
            return 1;
        }
        try {
            return convertFile(input) ? 0 : 1;
        } catch (SpecParseException e) {
            return abort(e, 0);
        }
    }

    /** 디렉터리 안의 모든 명세 파일(DOCX/HWP/HWPX, IR JSON)을 찾아 각각 변환한다. */
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
            err("디렉터리에서 변환할 명세 파일(.docx, .hwp, .hwpx, .json)을 찾지 못했습니다: " + input);
            return 1;
        }
        if (!prepareEngine(targets)) {
            return 1;
        }

        int failureCount = 0;
        for (int i = 0; i < targets.size(); i++) {
            Path target = targets.get(i);
            spec.commandLine().getOut().println("=== " + input.relativize(target) + " ===");
            try {
                if (!convertFile(target)) {
                    failureCount++;
                }
            } catch (SpecParseException e) {
                return abort(e, targets.size() - i - 1);
            }
        }
        // llm 엔진은 배치 전체에 쓴 토큰도 알린다. LLM이 답한 뒤에 실패한 문서의 토큰도 과금되므로 합계에 든다
        spec.commandLine().getOut().println(
                String.format("%d개 중 %d개 변환 성공, %d개 실패", targets.size(), targets.size() - failureCount, failureCount)
                        + (llmTokens == null ? "" : " · LLM 토큰 합계 " + tokens(llmTokens)));
        return failureCount == 0 ? 0 : 1;
    }

    /**
     * 명세 문서를 변환할 엔진을 정한다. llm 엔진은 파일을 하나라도 변환하기 전에 회사와 키를 정한다.
     * 키가 없다는 것을 첫 문서에서야 알면 그 앞의 파일만 변환된 채 끝나기 때문이다.
     * rule 엔진과, 변환할 명세 문서 없이 IR JSON만 있는 실행은 회사를 정하지 않고 환경변수도 읽지 않는다.
     * IR JSON은 엔진과 무관하게 바로 생성하므로 LLM 키가 없어도 변환돼야 한다.
     *
     * @param targets 이번 실행에서 변환할 파일
     * @return 변환을 시작해도 되면 true. 회사를 정하지 못했으면 이유를 알리고 false
     */
    private boolean prepareEngine(List<Path> targets) {
        if (!LLM_ENGINE.equalsIgnoreCase(engine) || targets.stream().noneMatch(DocumentReaders::isDocument)) {
            return true;
        }
        Map<String, String> env = environment.get();
        try {
            // llm은 회사를 정하지 못하면 rule로 가지 않고 예외로 끝나므로 결과는 늘 UseLlm이다
            ProviderResolver.Resolution.UseLlm resolved = (ProviderResolver.Resolution.UseLlm)
                    new ProviderResolver(providers, env).resolve(ProviderResolver.Engine.LLM, provider);
            LlmProviderFactory chosen = resolved.provider();
            String chosenModel = modelOf(chosen, env);
            checkModelProvider(chosen, chosenModel);
            LlmSettings settings = LlmSettings.of(chosenModel, effortOf(chosen, env), resolved.apiKey())
                    .withRequestTimeout(chosen.defaultRequestTimeout())
                    .withWorkspaceId(workspaceOf(chosen, env));
            LlmSpecParser.Options options = llmOptions();
            specParser = llmParsers.create(chosen, settings, options);
            llmTokens = TokenUsage.NONE;
            // 문서를 외부로 보내는 것은 사용자가 켠 일이지만, 무엇을 어디로 보내는지는 실행마다 한 번 알린다
            err("문서 내용을 " + chosen.id() + "(" + settings.model() + ", 추론 깊이 " + settings.effort()
                    + ")로 보냅니다. 비공개 문서는 --engine rule로 변환하세요.");
            pruneCache(options.cache());
            return true;
        } catch (IllegalArgumentException e) {
            // 회사를 정하지 못했거나(ProviderResolutionException) 키·모델이 설정에 맞지 않을 때다. 어느 메시지에도 키 값은 없다
            err(e.getMessage());
            return false;
        }
    }

    /** --model, H2SPEC_LLM_MODEL, 회사별 기본 모델 순으로 정한다. 빈 값은 주지 않은 것으로 본다 */
    private String modelOf(LlmProviderFactory chosen, Map<String, String> env) {
        if (model != null && !model.isBlank()) {
            return model.strip();
        }
        String fromEnvironment = env.get(MODEL_ENVIRONMENT_VARIABLE);
        return fromEnvironment != null && !fromEnvironment.isBlank() ? fromEnvironment.strip() : chosen.defaultModel();
    }

    /**
     * 모델이 다른 회사의 모델로 보이면 문서를 보내기 전에 멈춘다. 회사를 키 환경변수로 고른 채 다른 회사용 H2SPEC_LLM_MODEL이 남아 있으면
     * 문서 내용이 뜻하지 않은 회사로 간 뒤에야 모르는 모델 오류가 나기 때문이다. 어느 회사도 알아보지 못하는 이름은 그대로 보낸다.
     *
     * @throws IllegalArgumentException 다른 회사만 알아보는 모델일 때
     */
    private void checkModelProvider(LlmProviderFactory chosen, String chosenModel) {
        if (chosen.recognizesModel(chosenModel)) {
            return;
        }
        for (LlmProviderFactory other : providers) {
            if (other != chosen && other.recognizesModel(chosenModel)) {
                throw new IllegalArgumentException("모델 '" + chosenModel + "'은 " + other.id() + "의 모델로 보이는데 회사가 "
                        + chosen.id() + "로 정해져 문서를 보내지 않았습니다. 회사(--provider, H2SPEC_LLM_PROVIDER, 키 환경변수)와 "
                        + "모델(--model, H2SPEC_LLM_MODEL) 가운데 틀린 쪽을 고치세요");
            }
        }
    }

    /**
     * --effort, H2SPEC_LLM_EFFORT, 회사별 기본값 순으로 정한다. 빈 값은 주지 않은 것으로 보고, 대소문자는 가리지 않는다.
     * 회사가 받는 값을 알려 주면 부르기 전에 확인한다. 틀린 값으로 문서마다 호출이 거절되는 것보다 먼저 알리는 편이 낫다.
     *
     * @throws IllegalArgumentException 회사가 받지 않는 값일 때
     */
    private String effortOf(LlmProviderFactory chosen, Map<String, String> env) {
        String source = "--effort";
        String value = effort;
        if (value == null || value.isBlank()) {
            source = EFFORT_ENVIRONMENT_VARIABLE;
            value = env.get(EFFORT_ENVIRONMENT_VARIABLE);
        }
        if (value == null || value.isBlank()) {
            return chosen.defaultEffort();
        }
        String level = value.strip().toLowerCase(Locale.ROOT);
        List<String> levels = chosen.effortLevels();
        if (!levels.isEmpty() && !levels.contains(level)) {
            throw new IllegalArgumentException(source + " 값 '" + value.strip() + "'은 " + chosen.id()
                    + "가 받는 추론 깊이가 아닙니다. 쓸 수 있는 값: " + String.join(", ", levels));
        }
        return level;
    }

    /** 회사의 워크스페이스 환경변수 중 값이 있는 첫 것. 없으면 null이라 보내지 않는다 (한 워크스페이스에 묶인 키는 필요 없다) */
    private static String workspaceOf(LlmProviderFactory chosen, Map<String, String> env) {
        for (String name : chosen.workspaceEnvironmentVariables()) {
            String value = env.get(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * 결과 캐시에서 오래 쓰지 않은 응답을 지운다. 프롬프트나 모델이 바뀌면 이전 응답은 다시 쓰이지 않고 쌓이기만 해서, llm 엔진으로
     * 변환하는 실행마다 한 번 정리한다. 정리하지 못해도 변환은 그대로 한다.
     */
    private void pruneCache(ExtractionCache cache) {
        try {
            int removed = cache.prune(ExtractionCache.DEFAULT_MAX_IDLE, Instant.now());
            if (removed > 0) {
                err("결과 캐시에서 " + ExtractionCache.DEFAULT_MAX_IDLE.toDays() + "일 넘게 쓰지 않은 응답 " + removed + "개를 지웠습니다");
            }
        } catch (IOException e) {
            err("결과 캐시를 정리하지 못했습니다: " + e.getMessage());
        }
    }

    /** 캐시·교차검증 옵션을 llm 엔진 설정에 옮긴다. 경고는 라이브러리가 기본으로 버리므로 표준 오류로 받는다 */
    private LlmSpecParser.Options llmOptions() {
        LlmSpecParser.Options options = LlmSpecParser.Options.defaults()
                .withCrossCheck(!noCrossCheck)
                .withWarnings(this::err);
        if (noCache) {
            // 끄라는 뜻이 위치를 바꾸라는 뜻보다 분명하므로 --cache-dir과 함께 와도 끈다
            return options.withCache(ExtractionCache.disabled());
        }
        return cacheDir == null ? options : options.withCache(ExtractionCache.at(cacheDir));
    }

    /**
     * 키 오류(AUTH), 모델 이름·주소 오류(SETTINGS), 잔액·사용 한도 문제(QUOTA), 회사가 요구한 긴 대기(RETRY_LATER)로 실행을 멈춘다.
     * 남은 문서도 모두 같은 이유로 실패하므로 하나씩 실패로 세지 않는다.
     * 변환 요약을 내지 않으므로 남은 파일을 변환하지 않았다는 것을 따로 알린다.
     */
    private int abort(SpecParseException e, int remaining) {
        e.engine().ifPresent(this::reportEngine);
        err(e.getMessage());
        if (remaining > 0) {
            String reason = switch (e.kind()) {
                case AUTH -> "API 키나 권한 오류";
                case QUOTA -> "LLM 잔액·사용 한도 문제";
                case RETRY_LATER -> "LLM 회사가 알려 준 시간 전에는 다시 부를 수 없는 상태";
                default -> "LLM 설정 오류";
            };
            err(reason + "라 남은 파일 " + remaining + "개는 변환하지 않고 멈춥니다");
        }
        // 변환 요약을 내지 않아도 멈추기 전까지 과금된 토큰은 알린다
        if (llmTokens != null && !llmTokens.equals(TokenUsage.NONE)) {
            spec.commandLine().getOut().println("멈추기 전까지 LLM 토큰 합계 " + tokens(llmTokens));
        }
        return 1;
    }

    /** 다른 문서도 같은 이유로 실패하는 오류. 실행 전체를 멈춘다 */
    private static boolean stopsRun(SpecParseException e) {
        return e.kind() == SpecParseException.Kind.AUTH || e.kind() == SpecParseException.Kind.SETTINGS
                || e.kind() == SpecParseException.Kind.QUOTA || e.kind() == SpecParseException.Kind.RETRY_LATER;
    }

    private boolean isSupportedSpecFile(Path path) {
        return DocumentReaders.isDocument(path)
                || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(JSON_EXTENSION);
    }

    /**
     * 파일 하나(명세 문서 또는 IR JSON)를 변환한다. 실패해도 배치 전체를 중단하지 않도록 boolean으로 결과를 알린다.
     *
     * @throws SpecParseException 다른 문서도 같은 이유로 실패하는 오류({@link SpecParseException.Kind#AUTH},
     *                            {@link SpecParseException.Kind#SETTINGS}, {@link SpecParseException.Kind#QUOTA},
     *                            {@link SpecParseException.Kind#RETRY_LATER})일 때만.
     *                            부르는 쪽이 실행 전체를 멈춘다
     */
    private boolean convertFile(Path file) throws SpecParseException {
        try {
            if (DocumentReaders.isDocument(file)) {
                SpecParseResult parsed = specParser.parse(file);
                reportEngine(parsed.engine());
                return convertDocument(parsed.apis(), parsed.skipped(), file);
            } else {
                // 명세 문서가 아닌 파일은 모두 IR JSON으로 본다
                generateFromInputIr(file);
                return true;
            }
        } catch (SpecParseException e) {
            if (stopsRun(e)) {
                throw e;
            }
            // 429·5xx·네트워크·중단된 응답(RETRYABLE)과 그 밖의 실패는 그 문서만 실패다. --engine llm을 고른 사용자의 뜻과 다르므로
            // rule 엔진으로 대신 변환하지 않는다. rule 엔진은 FAILED만 낸다
            e.engine().ifPresent(this::reportEngine);
            err(e.getMessage());
            return false;
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            err(e.getMessage());
            return false;
        } catch (IOException e) {
            err("변환 실패 (" + file + "): " + e.getMessage());
            return false;
        } catch (RuntimeException e) {
            // 예기치 않은 오류도 그 파일만 실패로 센다. 디렉터리 변환이 파일 하나 때문에 요약 없이 멈추지 않게 한다
            StackTraceElement[] trace = e.getStackTrace();
            err("변환 실패 (" + file + "): 예기치 않은 오류 " + e + (trace.length == 0 ? "" : " (" + trace[0] + ")"));
            return false;
        }
    }

    /**
     * llm 엔진으로 변환한 문서마다 쓴 회사·모델과 토큰 사용량을 알리고 실행 합계에 더한다.
     * 변환에 실패한 문서도 LLM이 답한 뒤였으면(출력 잘림, 거절 등) 그 호출이 과금되므로 똑같이 알린다.
     * 토큰 사용량은 실행마다 달라 IR에 넣지 않으므로 여기에만 남는다. rule 엔진은 알리지 않는다.
     * "캐시 사용"은 h2spec 결과 캐시에서 꺼냈다는 뜻이고, 그때는 LLM을 부르지 않아 토큰이 0이다.
     */
    private void reportEngine(SpecParseResult.Engine used) {
        if (!LLM_ENGINE.equals(used.name())) {
            return;
        }
        llmTokens = plus(llmTokens == null ? TokenUsage.NONE : llmTokens, used.tokenUsage());
        spec.commandLine().getOut().println("엔진: " + used.name() + " (" + used.provider() + " / " + used.model()
                + ", 추론 깊이 " + used.effort() + "), "
                + (used.cacheUsed() ? "캐시 사용" : "캐시 없음") + ", 토큰 " + tokens(used.tokenUsage()) + " (thinking 포함)");
    }

    /**
     * 토큰 사용량을 "입력 N / 출력 M"으로 적는다. N은 보낸 입력 전체로, 캐시와 상관없는 입력에 회사의 프롬프트 캐시에서 읽은 입력과
     * 캐시에 쓴 입력을 더한 값이다. 프롬프트 캐시가 맞았는지에 따라 값이 흔들리지 않고 같은 문서면 같은 크기로 보인다.
     * 어댑터가 회사마다 다른 집계를 이 세 칸으로 나눠 두므로 회사와 상관없이 같은 뜻이다.
     * 프롬프트 캐시에서 읽은 입력은 단가가 입력의 10분의 1이라 따로도 적는다.
     * M은 출력 단가로 과금되는 출력 전부라 thinking을 포함한다.
     */
    private static String tokens(TokenUsage usage) {
        String cacheRead = usage.cacheRead() == 0 ? "" : " (프롬프트 캐시 읽기 " + usage.cacheRead() + ")";
        return "입력 " + (usage.input() + usage.cacheRead() + usage.cacheWrite()) + cacheRead + " / 출력 " + usage.output();
    }

    private static TokenUsage plus(TokenUsage total, TokenUsage usage) {
        return new TokenUsage(total.input() + usage.input(), total.cacheRead() + usage.cacheRead(),
                total.cacheWrite() + usage.cacheWrite(), total.output() + usage.output());
    }

    /**
     * 문서의 IR을 모두 쓴 뒤 오퍼레이션마다 따로 코드를 생성한다. 한 오퍼레이션의 생성 실패로 나머지 IR과 코드를 잃지 않기 위해서다.
     * 생성에 실패한 오퍼레이션은 파서가 건너뛴 오퍼레이션(skipped)과 같은 형식으로 알리고, IR은 검수하도록 남긴다.
     * 코드를 생성한 오퍼레이션이 하나도 없을 때만 문서를 실패로 센다.
     */
    boolean convertDocument(List<ParsedApi> apis, List<String> skipped, Path sourceFile) throws IOException {
        // 뒤 단계에서 무엇이 실패하든 빠지지 않도록 가장 먼저 알린다
        warnSkipped(skipped, sourceFile);
        Map<String, Path> irFiles = writeIrFiles(apis, sourceFile);
        int generated = 0;
        for (Map.Entry<String, Path> irFile : irFiles.entrySet()) {
            try {
                // 덮어쓰기 옵션은 쓴 IR에 이미 들어 있다
                generate(new IrLoader().load(irFile.getValue()));
                generated++;
            } catch (IllegalArgumentException | UnsupportedOperationException | IOException e) {
                warnSkipped(List.of(irFile.getKey() + ": " + e.getMessage()), sourceFile);
            }
        }
        if (generated == 0) {
            err("변환 실패 (" + sourceFile + "): 코드를 생성한 오퍼레이션이 없습니다");
            return false;
        }
        return true;
    }

    /**
     * 문서의 IR을 오퍼레이션마다 쓰고, 쓴 파일을 apiId별로 문서 순서대로 돌려준다.
     * 한 문서 안에서 apiId가 겹치면 뒤 오퍼레이션의 IR만 남으므로 그 파일에서 코드를 두 번 만들지 않게 한 번만 담는다.
     */
    private Map<String, Path> writeIrFiles(List<ParsedApi> apis, Path sourceFile) throws IOException {
        Path irDir = Files.createDirectories(output.resolve("ir"));
        Map<String, Path> irFiles = new LinkedHashMap<>();
        for (ParsedApi parsed : apis) {
            // apiId는 파일 이름이 된다. 두 엔진은 안전한 꼴만 내지만 SpecParser는 공개 인터페이스라 출력 디렉터리 밖에 쓰지 않게 먼저 본다
            if (!JavaNameRules.isValidApiId(parsed.apiId())) {
                warnSkipped(List.of(parsed.apiId() + ": apiId는 영문자로 시작하고 영문자·숫자·_만 써야 합니다"), sourceFile);
                continue;
            }
            Path irFile = irDir.resolve(parsed.apiId() + ".json");
            // 쓰고 난 뒤에 알리면 앞서 쓴 IR은 이미 사라진 뒤다
            warnOnApiIdOverwrite(parsed.apiId(), sourceFile);
            Files.writeString(irFile, withOverrides(parsed.ir()).toPrettyString());
            spec.commandLine().getOut().println("IR 추출: " + irFile);
            irFiles.put(parsed.apiId(), irFile);
        }
        return irFiles;
    }

    /**
     * 입력으로 받은 IR JSON에서 코드를 생성한다. 빌드가 커밋된 IR을 바꾸면 안 되므로 파일은 고치지 않고
     * 덮어쓰기 옵션은 생성할 때만 적용한다.
     */
    private void generateFromInputIr(Path irJson) throws IOException {
        IrSpec ir = new IrLoader().load(irJson);
        warnOnApiIdOverwrite(ir.api().apiId(), irJson);
        if (successCode != null) {
            ir = withSuccessCode(ir, successCode);
        }
        if (format != null) {
            ir = withResponseFormat(ir, format.toUpperCase(Locale.ROOT));
        }
        if (targetPackage != null) {
            ir = withTargetPackage(ir, targetPackage);
        }
        generate(ir);
    }

    private void generate(IrSpec ir) throws IOException {
        List<Path> written = new CodeGenerator().generate(ir, output);
        written.forEach(path -> spec.commandLine().getOut().println("생성: " + path));
        // 키 없이 여는 API도 있지만, 문서의 요청 표에서 키 파라미터가 빠졌을 수도 있다. 부를 때 인증 오류가 나기 전에 알린다
        if (!AuthKey.of(ir.api()).hasKey()) {
            err("인증키 파라미터를 찾지 못해 인증키 없이 부르는 클라이언트를 만들었습니다: " + ir.api().apiId()
                    + " — 키가 필요한 API면 IR의 api.auth나 요청 파라미터에 인증키를 적어 주세요");
        }
    }

    /**
     * 덮어쓰기 옵션을 명세 문서에서 새로 쓰는 IR에도 반영한다. IR이 검수 단위라 생성 코드와 어긋나면 안 된다.
     * generator의 IrSpec으로 다시 쓰면 generator가 모르는 필드(metadata, knownErrorCodes 등)가 사라지므로
     * JSON 트리에서 해당 값만 바꾼다.
     */
    private ObjectNode withOverrides(ObjectNode parsedIr) {
        ObjectNode ir = parsedIr.deepCopy();
        if (successCode != null) {
            override(ir, "/api/errorSpec", "successResultCode", successCode, "--success-code로 성공코드를");
        }
        if (format != null) {
            String previous = ir.at("/api/responseFormat").asText();
            String responseFormat = format.toUpperCase(Locale.ROOT);
            override(ir, "/api", "responseFormat", responseFormat, "--format으로 응답 포맷을");
            // 생성 클라이언트는 포맷 파라미터 힌트의 값을 그대로 보내므로, IR JSON 입력과 같이 힌트 값도 새 형식으로 바꾼다
            JsonNode hint = ir.at("/generatorHints/responseFormatParameter");
            if (hint.isObject() && hint.path("value").isTextual()) {
                String value = formatValue(hint.path("value").asText(), previous, responseFormat);
                if (value != null) {
                    ((ObjectNode) hint).put("value", value);
                }
            }
        }
        if (targetPackage != null) {
            String apiPackage = operationPackage(targetPackage, ir.at("/api/apiId").asText());
            override(ir, "/generatorHints", "targetPackage", apiPackage, "--package로 생성 패키지를");
        }
        return ir;
    }

    /**
     * 값이 문서가 아니라 옵션에서 왔음을 검수자가 알 수 있게 원래 값과 함께 노트를 남긴다.
     * 원래 값과 같으면 바꿀 것도 알릴 것도 없다.
     */
    private void override(ObjectNode ir, String parentPointer, String field, String value, String noteLabel) {
        ObjectNode parent = ir.withObject(parentPointer);
        String previous = parent.path(field).asText();
        if (value.equals(previous)) {
            return;
        }
        parent.put(field, value);
        ir.withArray("/metadata/reviewNotes").add(CLI_NOTE_PREFIX + noteLabel + " 바꿈: " + previous + " → " + value);
        ir.withObject("/metadata").put("manualReviewRequired", true);
    }

    /** 문서의 일부 오퍼레이션만 변환됐으면 빠진 오퍼레이션과 이유를 알린다. 성공으로 집계돼 놓치기 쉽다 */
    private void warnSkipped(List<String> skipped, Path source) {
        for (String reason : skipped) {
            err("건너뛴 오퍼레이션 (" + source.getFileName() + "): " + reason);
        }
    }

    /**
     * 배치는 모든 문서를 한 출력 디렉터리에 쏟으므로 apiId가 겹치면 앞선 산출물이 조용히 사라진다.
     * 성공으로 집계되기 때문에 알리지 않으면 알아채기 어렵다. 명세 문서는 IR을 쓰기 전에, IR JSON 입력은 생성하기 전에 부른다.
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
        return new IrSpec(api.withResponseFormat(responseFormat),
                withFormatValue(ir.generatorHints(), api.responseFormat(), responseFormat));
    }

    /** 포맷 파라미터 힌트의 값이 이전 응답 형식이면 같은 대소문자로 새 형식을 넣는다 */
    private static GeneratorHints withFormatValue(GeneratorHints hints, String previous, String responseFormat) {
        GeneratorHints.ResponseFormatParameter hint = hints.responseFormatParameter();
        String value = hint == null ? null : formatValue(hint.value(), previous, responseFormat);
        if (value == null) {
            return hints;
        }
        return new GeneratorHints(hints.targetPackage(), new GeneratorHints.ResponseFormatParameter(hint.name(), value));
    }

    /**
     * 포맷 파라미터 힌트의 새 값. 명세 문서에서 쓰는 IR과 IR JSON 입력이 같은 규칙으로 바꿔야 두 경로의 생성 코드가 같다.
     *
     * @return 힌트 값이 이전 응답 형식이면 그 대소문자(전부 대문자면 대문자, 아니면 소문자)로 쓴 새 형식. 다른 값이면 바꾸지 않으므로 null
     */
    private static String formatValue(String hintValue, String previous, String responseFormat) {
        if (hintValue == null || !hintValue.equalsIgnoreCase(previous)) {
            return null;
        }
        return hintValue.equals(hintValue.toUpperCase(Locale.ROOT))
                ? responseFormat.toUpperCase(Locale.ROOT) : responseFormat.toLowerCase(Locale.ROOT);
    }

    /** 성공코드만 바꾼다. 결과코드 위치 후보까지 지우면 생성 클라이언트가 문서에 적힌 결과코드 이름을 인터셉터에 넘기지 못한다 */
    private IrSpec withSuccessCode(IrSpec ir, String code) {
        ApiSpec api = ir.api();
        ErrorSpec errorSpec = new ErrorSpec(code, api.errorSpec().resultCodeFieldCandidates());
        return new IrSpec(api.withErrorSpec(errorSpec), ir.generatorHints());
    }

    private IrSpec withTargetPackage(IrSpec ir, String basePackage) {
        return new IrSpec(ir.api(), new GeneratorHints(operationPackage(basePackage, ir.api().apiId()),
                ir.generatorHints().responseFormatParameter()));
    }

    /**
     * 오퍼레이션마다 apiId를 소문자화한 하위 패키지를 붙인다.
     * 문서에서 쓰는 IR과 IR JSON 입력의 생성 코드가 같은 값을 쓰도록 한 곳에서 만든다.
     */
    private String operationPackage(String basePackage, String apiId) {
        return basePackage + "." + apiId.toLowerCase(Locale.ROOT);
    }

    private void err(String message) {
        spec.commandLine().getErr().println(message);
    }

    /** 정한 회사와 설정으로 llm 엔진을 만든다. 실제로는 {@link LlmSpecParser#of}이고, 테스트는 네트워크를 쓰지 않는 가짜 엔진을 돌려준다 */
    @FunctionalInterface
    interface LlmParserFactory {

        /**
         * @param provider ProviderResolver가 정한 회사
         * @param settings 모델, 추론 깊이, 키
         * @param options  결과 캐시, 교차검증, 경고를 받을 곳
         * @return 명세 문서를 변환할 llm 엔진
         */
        SpecParser create(LlmProviderFactory provider, LlmSettings settings, LlmSpecParser.Options options);
    }

    /** --provider 도움말에 고를 수 있는 회사를 적는다. 회사를 더하면(LlmProviders.ALL) 도움말도 따라 바뀐다 */
    static final class ProviderIds implements Iterable<String> {

        @Override
        public Iterator<String> iterator() {
            return LlmProviders.ids().iterator();
        }
    }
}
