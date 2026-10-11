package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.DocumentReaders;
import kr.go.h2spec.parser.ParsedApi;
import kr.go.h2spec.parser.RuleEngine;
import kr.go.h2spec.parser.SpecParseException;
import kr.go.h2spec.parser.SpecParseResult;
import kr.go.h2spec.parser.SpecParser;
import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import kr.go.h2spec.parser.llm.crosscheck.CrossCheckResult;
import kr.go.h2spec.parser.llm.crosscheck.CrossChecker;
import kr.go.h2spec.parser.llm.normalize.IrNormalizer;
import kr.go.h2spec.parser.llm.normalize.LlmMetadata;
import kr.go.h2spec.parser.llm.normalize.NormalizedOperation;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import kr.go.h2spec.parser.llm.validate.ValidationFeedback;
import kr.go.h2spec.parser.llm.validate.ValidationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * llm 엔진의 {@link SpecParser} (설계 3.4). 문서 하나를 아래 순서로 변환한다.
 * <ol>
 *   <li>확장자로 고른 Reader({@link DocumentReaders})로 Block 목록을 읽는다. 교차검증도 같은 목록을 쓴다.</li>
 *   <li>{@link DocumentSerializer}로 직렬화하고, 시스템 프롬프트·문서·작업 지시·스키마로 요청을 만든다({@link ExtractionPrompt}).</li>
 *   <li>결과 캐시({@link ExtractionCache})를 거쳐 회사 어댑터({@link LlmClient})를 문서당 한 번 부른다(설계 4.4).</li>
 *   <li>응답을 중간 스키마로 엄격하게 읽는다. 읽지 못하면 한 번만 다시 부르고, 그래도 못 읽으면 그 문서는 실패다.</li>
 *   <li>원문 대조({@link ExtractionValidator})에서 버린 것이 있으면({@link ValidationFeedback}) 그것을 알려 한 번 더 부르고, 검증을 더 많이 통과한 결과를 쓴다.</li>
 *   <li>정규화({@link IrNormalizer}), 같은 Block 목록으로 돌린 rule 엔진과의 교차검증({@link CrossChecker})을 거친다.</li>
 * </ol>
 * 호출 실패와 쓸 수 없는 응답은 {@link SpecParseException}의 종류로 바꿔 CLI가 처리를 가르게 한다(설계 7.3).
 * 키 오류는 {@code AUTH}, 재시도한 뒤에도 남은 호출 한도·서버·네트워크 오류는 {@code RETRYABLE}, 나머지(잘못된 요청, 출력 잘림, 거절,
 * JSON 파싱 실패, 변환한 오퍼레이션 없음)는 {@code FAILED}다. 1단계에는 나눠 부르기가 없어 출력이 잘리면 그 문서를 실패로 알린다.
 * 메시지는 사용자에게 그대로 보여 줄 한 줄이고 API 키를 담지 않는다. 어댑터가 회사의 오류 글에서 키를 가리고, 키를 아는 경우 여기서도 한 번 더 가린다.
 */
public final class LlmSpecParser implements SpecParser {

    /**
     * 출력 토큰 상한의 기본값. thinking(추론) 토큰도 이 안에서 쓰인다(설계 10장).
     * 실제 호출에서 보이는 출력은 오퍼레이션당 약 2천~4천 토큰이었다(기상청 중기예보 4개에 1만 6천여 토큰). 나눠 부르기가 없어 출력이
     * 잘리면 문서 전체가 실패하므로, 6만 4천이면 오퍼레이션이 15개를 넘는 문서에서 잘릴 수 있어 Claude Opus 5.5·Sonnet 5.5의
     * 출력 한도인 12만 8천으로 두었다. 상한일 뿐이라 쓰지 않은 토큰은 과금되지 않고, 어댑터가 스트리밍으로 받아 출력이 길어도
     * HTTP 타임아웃에 걸리지 않는다. 결과 캐시 키에 들어가므로 바꾸면 같은 문서도 다시 부른다.
     * 출력 한도가 다른 회사의 모델은 2-1에서 어댑터마다 확인한다.
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 128_000;

    /** {@link SpecParseResult.Engine#name()}에 적는 엔진 이름 */
    private static final String ENGINE = "llm";
    /** 첫 호출과, JSON으로 읽지 못했을 때의 한 번 더 (설계 4.4) */
    private static final int MAX_ATTEMPTS = 2;

    /**
     * LLM 응답을 중간 스키마로 읽는다. 스키마는 모든 필드를 required로 두고 null을 쓰지 않으므로(설계 4.2), 빠진 필드·null·모르는 필드·모르는 enum 값은
     * 모두 스키마를 따르지 않은 응답이다. 배열 안의 null, 숫자로 쓴 enum, 문자열 자리의 숫자처럼 Jackson이 너그럽게 받는 것도 막아
     * 검증·정규화가 스키마대로의 값만 보게 한다.
     */
    private static final ObjectMapper STRICT = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
            // JSON 하나 뒤에 다른 글이 붙어 있으면 JSON 하나만 낸 응답이 아니다
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            // ALLOW_COERCION_OF_SCALARS는 숫자·불리언 자리만 막으므로 문자열 자리는 따로 막는다
            .withCoercionConfig(LogicalType.Textual, config -> config
                    .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                    .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
            .defaultSetterInfo(JsonSetter.Value.construct(Nulls.FAIL, Nulls.FAIL))
            .build();

    private final LlmClient client;
    private final String provider;
    private final String model;
    private final String effort;
    private final Options options;
    /** 메시지에서 한 번 더 가릴 API 키. 클라이언트를 직접 받았으면 모르므로 null */
    private final String apiKey;
    private final DocumentSerializer serializer;
    private final RuleRunner rule;
    private final ExtractionPrompt prompt = ExtractionPrompt.load();
    private final LlmMetadata metadata;

    /**
     * 만들어 둔 클라이언트로 변환한다. 녹화 재생처럼 어댑터가 아닌 클라이언트도 받는다.
     *
     * @param client   회사 어댑터
     * @param provider 회사 ID ({@code claude}). 엔진 정보, IR metadata, 결과 캐시 키에 쓴다
     * @param model    클라이언트를 만든 설정의 모델 ID
     * @param effort   클라이언트를 만든 설정의 추론 깊이
     * @param options  출력 한도, 결과 캐시, 교차검증, 경고를 받을 곳
     */
    public LlmSpecParser(LlmClient client, String provider, String model, String effort, Options options) {
        this(client, provider, model, effort, options, null, new DocumentSerializer(), LlmSpecParser::runRuleEngine);
    }

    /** 테스트가 길이 상한이 작은 직렬화기나 실패하는 rule 엔진으로 바꿀 때 쓴다 */
    LlmSpecParser(LlmClient client, String provider, String model, String effort, Options options, String apiKey,
                  DocumentSerializer serializer, RuleRunner rule) {
        this.client = Objects.requireNonNull(client, "client");
        this.provider = requireText(provider, "provider");
        this.model = requireText(model, "model");
        this.effort = requireText(effort, "effort");
        this.options = Objects.requireNonNull(options, "options");
        this.apiKey = apiKey == null || apiKey.isEmpty() ? null : apiKey;
        this.serializer = Objects.requireNonNull(serializer, "serializer");
        this.rule = Objects.requireNonNull(rule, "rule");
        this.metadata = LlmMetadata.of(provider, model, effort, prompt);
    }

    /**
     * 회사의 팩토리로 클라이언트를 만든다. 회사·모델·추론 깊이를 클라이언트와 같은 설정에서 읽어, 엔진 정보·IR metadata·결과 캐시 키가
     * 실제로 부른 설정과 어긋나지 않는다.
     *
     * @param provider 회사 ({@code ProviderResolver}가 정한 것)
     * @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수
     * @param options  출력 한도, 결과 캐시, 교차검증, 경고를 받을 곳
     * @return llm 엔진
     */
    public static LlmSpecParser of(LlmProviderFactory provider, LlmSettings settings, Options options) {
        return new LlmSpecParser(provider.create(settings), provider.id(), settings.model(), settings.effort(), options,
                settings.apiKey(), new DocumentSerializer(), LlmSpecParser::runRuleEngine);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 변환한 오퍼레이션이 없으면 {@link SpecParseException.Kind#FAILED}를 던진다. 오퍼레이션을 하나도 찾지 못했으면 rule 엔진과
     * 같은 문구("문서에서 상세기능 명세를 찾지 못했습니다: 파일명")이고, 찾았지만 모두 건너뛰었으면 명세를 찾지 못한 것으로 읽히지 않게
     * 다른 문구에 건너뛴 이유를 붙인다. LLM은 이미 답했으므로 예외에 엔진 정보(토큰 사용량)를 담는다.
     */
    @Override
    public SpecParseResult parse(Path document) throws IOException, SpecParseException {
        SpecParseResult result = extract(document);
        if (result.apis().isEmpty()) {
            String fileName = document.getFileName().toString();
            String message = result.operations().isEmpty()
                    ? notFoundMessage(fileName, result.skipped())
                    : "변환할 수 있는 상세기능이 없습니다 (찾은 " + result.operations().size()
                            + "개 모두 지원 범위 밖이거나 검증에서 건너뜀): " + fileName
                            + " (" + String.join("; ", result.skipped()) + ")";
            throw new SpecParseException(SpecParseException.Kind.FAILED, message, null, result.engine());
        }
        return result;
    }

    /**
     * {@link #parse}와 같지만 변환한 오퍼레이션이 없어도 던지지 않는다. 지원 범위 밖으로 건너뛴 오퍼레이션의 IR까지 봐야 하는
     * 평가 도구(설계 9.4)가 쓴다. LLM을 부르지 못했거나 응답을 쓸 수 없으면 똑같이 던진다.
     *
     * @param document 명세 문서. 확장자로 형식을 정한다
     * @return 오퍼레이션마다의 결과와 엔진 정보
     * @throws IOException        문서를 읽지 못했을 때
     * @throws SpecParseException LLM 호출이 실패했거나 응답을 결과로 쓸 수 없을 때
     * @throws IllegalArgumentException 명세 문서(.docx, .hwp, .hwpx)가 아닐 때
     */
    public SpecParseResult extract(Path document) throws IOException, SpecParseException {
        DocumentReaders.Format format = DocumentReaders.find(document).orElseThrow(
                () -> new IllegalArgumentException("명세 문서(.docx, .hwp, .hwpx)가 아닙니다: " + document.getFileName()));
        return extract(format.reader().read(document), document.getFileName().toString(), format.sourceFormat());
    }

    /**
     * 이미 읽은 Block 목록을 변환한다. 변환한 오퍼레이션이 없어도 던지지 않는다. 문서를 따로 읽어 둔 평가 도구가 두 번 읽지 않게 한다.
     *
     * @param blocks       Reader가 읽은 문서의 블록
     * @param sourceFile   IR metadata에 적을 원본 파일 이름. 메시지와 경고에도 쓴다
     * @param sourceFormat IR metadata에 적을 원본 포맷 (DOCX, HWP, HWPX)
     * @return 오퍼레이션마다의 결과와 엔진 정보
     * @throws SpecParseException LLM 호출이 실패했거나 응답을 결과로 쓸 수 없을 때
     */
    public SpecParseResult extract(List<Block> blocks, String sourceFile, String sourceFormat) throws SpecParseException {
        LlmRequest request = prompt.wholeDocumentRequest(serialize(blocks, sourceFile), options.maxOutputTokens());
        Extraction extraction = call(request, sourceFile);
        ValidationResult validation = new ExtractionValidator().validate(extraction.value(), blocks);
        if (options.retryOnValidation()) {
            List<String> problems = ValidationFeedback.problems(validation);
            if (!problems.isEmpty()) {
                Checked checked = retry(prompt.retryRequest(request, problems), new Checked(extraction, validation),
                        blocks, sourceFile);
                extraction = checked.extraction();
                validation = checked.validation();
            }
        }
        List<NormalizedOperation> operations = new IrNormalizer(metadata).normalize(validation, sourceFile, sourceFormat);
        if (options.crossCheck()) {
            operations = crossCheck(operations, blocks, sourceFile, sourceFormat);
        }
        return result(operations, extraction);
    }

    /**
     * 원문 대조에서 버린 것을 알려 한 번 더 부르고, 검증을 더 많이 통과한 결과를 쓴다. 같으면 처음 결과를 쓴다.
     * 두 호출 모두 과금되므로 토큰은 합친다. 다시 부르기가 실패해도 처음 결과는 쓸 수 있어 경고만 남긴다.
     */
    private Checked retry(LlmRequest request, Checked first, List<Block> blocks, String sourceFile) {
        Extraction again;
        try {
            again = call(request, sourceFile);
        } catch (SpecParseException e) {
            SpecParseResult.TokenUsage spent = e.engine()
                    .map(engine -> add(first.extraction().spent(), engine.tokenUsage()))
                    .orElse(first.extraction().spent());
            warn(sourceFile, "검증 결과를 알려 다시 부르지 못해 처음 결과를 씁니다: " + e.getMessage());
            return new Checked(new Extraction(first.extraction().value(), false, spent), first.validation());
        }
        ValidationResult validation = new ExtractionValidator().validate(again.value(), blocks);
        boolean better = ValidationFeedback.better(first.validation(), validation);
        warn(sourceFile, "원문 대조에서 버린 항목이 있어 검증 결과를 알려 한 번 더 불렀습니다 (통과한 오퍼레이션 "
                + ValidationFeedback.keptOperations(first.validation()) + "개 → "
                + ValidationFeedback.keptOperations(validation) + "개, " + (better ? "다시 부른 결과를 씀" : "처음 결과를 씀") + ")");
        SpecParseResult.TokenUsage spent = add(first.extraction().spent(), again.spent());
        boolean fromCache = first.extraction().fromCache() && again.fromCache();
        return better
                ? new Checked(new Extraction(again.value(), fromCache, spent), validation)
                : new Checked(new Extraction(first.extraction().value(), fromCache, spent), first.validation());
    }

    private String serialize(List<Block> blocks, String sourceFile) throws SpecParseException {
        try {
            return serializer.serialize(blocks);
        } catch (DocumentTooLargeException e) {
            throw new SpecParseException(SpecParseException.Kind.FAILED,
                    "LLM에 보내지 않았습니다 (" + sourceFile + "): " + e.getMessage(), e);
        }
    }

    /**
     * 결과 캐시를 거쳐 부르고 중간 스키마로 읽는다. 읽지 못하면 한 번만 다시 부르고, 다시 부를 때는 캐시를 보지 않는다.
     * 캐시에서 꺼낸 응답이 읽히지 않았다면 다시 꺼내도 같기 때문이다. COMPLETE 응답은 읽힌 뒤에만 저장해, 읽히지 않는 응답이 캐시에 남아
     * 다음 실행에서도 되풀이되지 않게 한다. LLM이 답한 뒤의 실패는 그때까지 쓴 토큰을 예외에 담는다. 그 호출도 과금되기 때문이다.
     */
    private Extraction call(LlmRequest request, String sourceFile) throws SpecParseException {
        ExtractionCache.Key key = ExtractionCache.key(request, provider, model, effort);
        SpecParseResult.TokenUsage spent = SpecParseResult.TokenUsage.NONE;
        JsonProcessingException failure = null;
        boolean fromCache = false;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<LlmResponse> cached = attempt == 1 ? lookup(key, sourceFile) : Optional.empty();
            LlmResponse response = cached.isPresent() ? cached.get() : complete(request, sourceFile, spent);
            fromCache = cached.isPresent();
            if (cached.isEmpty()) {
                spent = add(spent, response.usage());
            }
            if (response.finish() == LlmResponse.Finish.TRUNCATED) {
                if (cached.isEmpty()) {
                    // 같은 요청은 같은 한도에 걸릴 것이라 다음 실행이 다시 부르지(과금하지) 않고 바로 실패하게 한다
                    remember(key, response, sourceFile);
                }
                throw truncated(sourceFile, response, fromCache, spent);
            }
            if (response.finish() == LlmResponse.Finish.REFUSED) {
                throw new SpecParseException(SpecParseException.Kind.FAILED,
                        "LLM이 요청을 거절해 변환하지 못했습니다 (" + sourceFile + ", " + provider + ")",
                        null, engine(fromCache, spent));
            }
            try {
                LlmExtraction extraction = parseExtraction(response.json());
                if (cached.isEmpty()) {
                    remember(key, response, sourceFile);
                }
                return new Extraction(extraction, cached.isPresent(), spent);
            } catch (JsonProcessingException e) {
                failure = e;
            }
        }
        throw new SpecParseException(SpecParseException.Kind.FAILED, "LLM 응답을 중간 스키마 JSON으로 읽지 못했습니다 ("
                + sourceFile + ", " + MAX_ATTEMPTS + "번 시도): " + describe(failure), failure, engine(fromCache, spent));
    }

    /** 스키마는 최상위에 객체를 요구한다. Jackson은 최상위 null을 예외 없이 null로 읽으므로 따로 막는다 */
    private static LlmExtraction parseExtraction(String json) throws JsonProcessingException {
        LlmExtraction extraction = STRICT.readValue(json, LlmExtraction.class);
        if (extraction == null) {
            throw new JsonMappingException(null, "최상위 값이 객체가 아니라 null입니다");
        }
        return extraction;
    }

    /** @param spent 이 문서에서 앞서 부른 호출(JSON을 읽지 못해 다시 부르기 전)의 토큰. 이번 호출이 실패하면 예외에 담는다 */
    private LlmResponse complete(LlmRequest request, String sourceFile, SpecParseResult.TokenUsage spent)
            throws SpecParseException {
        try {
            return client.complete(request);
        } catch (LlmException e) {
            throw failure(e, sourceFile, spent);
        }
    }

    /**
     * 어댑터가 이미 재시도한 뒤의 실패다. 종류만 CLI가 쓰는 종류로 옮긴다(설계 7.3).
     * 앞서 답을 받은 호출이 있을 때만 엔진 정보를 담는다. 실패한 호출 자체의 사용량은 회사가 알려 주지 않는다.
     */
    private SpecParseException failure(LlmException e, String sourceFile, SpecParseResult.TokenUsage spent) {
        String where = " (" + sourceFile + ", " + provider + ")";
        String detail = e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + redact(e.getMessage());
        SpecParseResult.Engine used = spent.equals(SpecParseResult.TokenUsage.NONE) ? null : engine(false, spent);
        return switch (e.kind()) {
            case AUTH -> new SpecParseException(SpecParseException.Kind.AUTH,
                    "LLM API 키 오류로 변환하지 못했습니다" + where + detail, cause(e), used);
            case RATE_LIMITED -> new SpecParseException(SpecParseException.Kind.RETRYABLE,
                    "LLM 호출 한도에 걸려 변환하지 못했습니다. 잠시 뒤 다시 실행하세요" + where + detail, cause(e), used);
            case UNAVAILABLE -> new SpecParseException(SpecParseException.Kind.RETRYABLE,
                    "LLM 서버나 네트워크 오류로 변환하지 못했습니다. 잠시 뒤 다시 실행하세요" + where + detail, cause(e), used);
            case BAD_REQUEST -> new SpecParseException(SpecParseException.Kind.FAILED,
                    "LLM이 요청을 받지 않아 변환하지 못했습니다" + where + detail, cause(e), used);
        };
    }

    private SpecParseException truncated(String sourceFile, LlmResponse response, boolean fromCache,
                                         SpecParseResult.TokenUsage spent) {
        String detail = fromCache
                ? "앞선 실행에서 같은 요청이 잘려 결과 캐시에 잘림 표시가 있어 다시 부르지 않음"
                : "출력 " + response.usage().outputTokens() + " 토큰, thinking 포함";
        return new SpecParseException(SpecParseException.Kind.FAILED, "LLM 출력이 출력 한도 " + options.maxOutputTokens()
                + " 토큰에 걸려 잘렸습니다 (" + sourceFile + ", " + detail + "). "
                + "나눠 부르기가 들어오기 전에는 출력이 잘리는 문서를 llm 엔진으로 변환하지 못합니다",
                null, engine(fromCache, spent));
    }

    /** 캐시를 읽지 못하면 없는 것으로 보고 부른다. 캐시는 비용을 아끼는 장치라 그 때문에 문서를 실패시키지 않는다 */
    private Optional<LlmResponse> lookup(ExtractionCache.Key key, String sourceFile) {
        try {
            return options.cache().find(key);
        } catch (IOException e) {
            warn(sourceFile, "결과 캐시를 읽지 못해 LLM을 부릅니다: " + reason(e));
            return Optional.empty();
        }
    }

    /** 저장하지 못해도 받은 결과는 그대로 쓴다. 이미 과금한 응답을 캐시 때문에 버리지 않는다 */
    private void remember(ExtractionCache.Key key, LlmResponse response, String sourceFile) {
        try {
            options.cache().store(key, response);
        } catch (IOException e) {
            warn(sourceFile, "결과 캐시에 쓰지 못했습니다: " + reason(e));
        }
    }

    /**
     * rule 엔진이 실패해도 LLM 결과는 그대로 쓴다(설계 5.3). 교차검증은 사람이 볼 신호를 만드는 단계라 결과를 막지 않는다.
     * rule 엔진만 찾은 오퍼레이션은 오퍼레이션 기록에 넣지 않고 경고로 알린다. 두 엔진의 결과를 한 문서에 섞으면 검수가 어려워진다.
     */
    private List<NormalizedOperation> crossCheck(List<NormalizedOperation> operations, List<Block> blocks,
                                                 String sourceFile, String sourceFormat) {
        CrossCheckResult checked;
        try {
            checked = new CrossChecker().check(operations, rule.assemble(blocks, sourceFile, sourceFormat));
        } catch (RuntimeException e) {
            warn(sourceFile, "rule 엔진이 실패해 교차검증을 건너뜁니다: " + reason(e));
            return operations;
        }
        checked.warnings().forEach(warning -> warn(sourceFile, warning));
        return checked.operations();
    }

    private SpecParseResult result(List<NormalizedOperation> operations, Extraction extraction) {
        List<SpecParseResult.Operation> records = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            NormalizedOperation operation = operations.get(i);
            String title = operation.title() == null || operation.title().isEmpty() ? null : operation.title();
            // 지원 범위 밖으로 건너뛴 오퍼레이션도 정규화한 IR을 담는다. 파일로 쓰지는 않고 교차검증과 평가에 쓴다
            ParsedApi api = operation.ir() == null ? null : new ParsedApi(operation.apiId(), operation.ir());
            records.add(new SpecParseResult.Operation(operation.operationName(), title, api, operation.skipReason(),
                    operation.outOfScope()));
            if (operation.skipReason() != null) {
                skipped.add(label(operation.operationName(), title, i) + ": " + operation.skipReason());
            }
        }
        return new SpecParseResult(records, skipped, engine(extraction.fromCache(), extraction.spent()));
    }

    /** 결과에 담는 엔진 정보. LLM이 답한 뒤의 실패에도 담아 CLI가 과금된 토큰을 알리게 한다(설계 7.4) */
    private SpecParseResult.Engine engine(boolean fromCache, SpecParseResult.TokenUsage spent) {
        return new SpecParseResult.Engine(ENGINE, provider, model, effort, fromCache, spent);
    }

    /** 건너뜀 알림의 이름. 검증의 알림과 같은 순서로 영문 오퍼레이션명, 국문 기능명, 순번을 쓴다 */
    private static String label(String operationName, String title, int position) {
        if (operationName != null) {
            return operationName;
        }
        return title != null ? title : "오퍼레이션 " + (position + 1);
    }

    /** rule 엔진({@code SpecBlockAssembler.notFoundMessage})과 같은 문구다. 엔진이 달라도 CLI의 실패 알림이 같게 읽힌다 */
    private static String notFoundMessage(String fileName, List<String> skipped) {
        String message = "문서에서 상세기능 명세를 찾지 못했습니다: " + fileName;
        return skipped.isEmpty() ? message : message + " (" + String.join("; ", skipped) + ")";
    }

    private void warn(String sourceFile, String message) {
        options.warnings().accept(sourceFile + ": " + message);
    }

    private String redact(String message) {
        return apiKey == null ? message : message.replace(apiKey, LlmSettings.MASKED_API_KEY);
    }

    /** 원인 사슬의 어느 메시지에든 키가 있으면 사슬을 붙이지 않고 가린 메시지만 남긴다. 스택 트레이스로도 키가 새지 않게 한다 */
    private Throwable cause(LlmException e) {
        if (apiKey != null) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains(apiKey)) {
                    return new LlmException(e.kind(), redact(e.getMessage()));
                }
            }
        }
        return e;
    }

    private static SpecParseResult.TokenUsage add(SpecParseResult.TokenUsage total, SpecParseResult.TokenUsage more) {
        return new SpecParseResult.TokenUsage(total.input() + more.input(), total.cacheRead() + more.cacheRead(),
                total.cacheWrite() + more.cacheWrite(), total.output() + more.output());
    }

    private static SpecParseResult.TokenUsage add(SpecParseResult.TokenUsage total, LlmResponse.Usage usage) {
        // thinking 토큰(reasoningTokens)은 출력 토큰에 이미 들어 있어 따로 더하지 않는다
        return new SpecParseResult.TokenUsage(total.input() + usage.inputTokens(),
                total.cacheRead() + usage.cacheReadInputTokens(), total.cacheWrite() + usage.cacheWriteInputTokens(),
                total.output() + usage.outputTokens());
    }

    /** Jackson의 메시지는 응답 글을 길게 옮기므로 원래 메시지와 위치만 쓴다 */
    private static String describe(JsonProcessingException e) {
        String location = e.getLocation() == null ? ""
                : " (" + e.getLocation().getLineNr() + "행 " + e.getLocation().getColumnNr() + "열)";
        return e.getOriginalMessage() + location;
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + "가 비었습니다");
        }
        return value;
    }

    private static RuleEngine.Result runRuleEngine(List<Block> blocks, String sourceFile, String sourceFormat) {
        return new RuleEngine().assemble(blocks, sourceFile, sourceFormat);
    }

    /** 교차검증에 쓸 rule 엔진. 테스트가 실패하는 rule 엔진으로 바꿀 때 쓴다 */
    @FunctionalInterface
    interface RuleRunner {

        RuleEngine.Result assemble(List<Block> blocks, String sourceFile, String sourceFormat);
    }

    /**
     * 읽은 중간 스키마와 그것을 얻은 방법.
     *
     * @param value     중간 스키마
     * @param fromCache 결과 캐시에서 꺼냈는지
     * @param spent     이번 변환에서 실제로 부른 호출의 토큰 사용량 합계. 캐시에서 꺼냈으면 호출하지 않아 0이다
     */
    private record Extraction(LlmExtraction value, boolean fromCache, SpecParseResult.TokenUsage spent) {
    }

    /** 읽은 중간 스키마와 그 검증 결과 */
    private record Checked(Extraction extraction, ValidationResult validation) {
    }

    /**
     * llm 엔진의 실행 설정. {@link #defaults()}에서 바꿀 것만 바꿔 쓴다.
     *
     * @param maxOutputTokens 출력 토큰 상한. thinking 토큰도 이 안에서 쓰인다. 결과 캐시 키에 들어가므로 바꾸면 같은 문서도 다시 부른다
     * @param cache           결과 캐시. {@code --no-cache}면 {@link ExtractionCache#disabled()}, {@code --cache-dir}면 그 디렉터리
     * @param crossCheck      rule 엔진과 교차검증할지. {@code --no-cross-check}면 false
     * @param warnings        CLI가 표준 오류로 알릴 경고를 받는다. rule 엔진만 찾은 오퍼레이션과, 교차검증이나 결과 캐시를 쓰지 못한 일이다.
     *                        "파일명: 내용" 꼴의 한 줄이다. {@link SpecParseResult}에는 이것을 담을 자리가 없어 따로 받는다
     * @param retryOnValidation 원문 대조에서 버린 것({@link ValidationFeedback})이 있으면 그것을 알려 한 번 더 부를지.
     *                        다시 부른 문서는 토큰을 두 번 쓴다
     */
    public record Options(int maxOutputTokens, ExtractionCache cache, boolean crossCheck, Consumer<String> warnings,
                          boolean retryOnValidation) {

        /** 출력 한도는 1 이상이고, 결과 캐시와 경고를 받을 곳은 늘 있다. 끄려면 꺼진 캐시와 버리는 곳을 준다 */
        public Options {
            if (maxOutputTokens < 1) {
                throw new IllegalArgumentException("출력 토큰 상한은 1 이상이어야 합니다: " + maxOutputTokens);
            }
            Objects.requireNonNull(cache, "cache");
            Objects.requireNonNull(warnings, "warnings");
        }

        /**
         * @return 기본 출력 한도, 기본 위치의 결과 캐시, 교차검증 켬, 경고는 버림, 검증 결과를 알려 다시 부르기 켬.
         *         경고를 표준 오류로 알리는 것은 CLI의 일이라 라이브러리가 직접 찍지 않는다. CLI는 {@link #withWarnings}로 받을 곳을 준다
         */
        public static Options defaults() {
            Consumer<String> ignore = warning -> { };
            return new Options(DEFAULT_MAX_OUTPUT_TOKENS, ExtractionCache.at(ExtractionCache.defaultDirectory()), true, ignore,
                    true);
        }

        /**
         * @param tokens 새 출력 토큰 상한
         * @return 출력 한도만 바꾼 설정
         */
        public Options withMaxOutputTokens(int tokens) {
            return new Options(tokens, cache, crossCheck, warnings, retryOnValidation);
        }

        /**
         * @param newCache 새 결과 캐시
         * @return 결과 캐시만 바꾼 설정
         */
        public Options withCache(ExtractionCache newCache) {
            return new Options(maxOutputTokens, newCache, crossCheck, warnings, retryOnValidation);
        }

        /**
         * @param enabled 교차검증할지
         * @return 교차검증 여부만 바꾼 설정
         */
        public Options withCrossCheck(boolean enabled) {
            return new Options(maxOutputTokens, cache, enabled, warnings, retryOnValidation);
        }

        /**
         * @param sink 경고를 받을 곳
         * @return 경고를 받을 곳만 바꾼 설정
         */
        public Options withWarnings(Consumer<String> sink) {
            return new Options(maxOutputTokens, cache, crossCheck, sink, retryOnValidation);
        }

        /**
         * @param enabled 원문 대조에서 버린 것을 알려 다시 부를지
         * @return 다시 부르기 여부만 바꾼 설정
         */
        public Options withRetryOnValidation(boolean enabled) {
            return new Options(maxOutputTokens, cache, crossCheck, warnings, enabled);
        }
    }
}
