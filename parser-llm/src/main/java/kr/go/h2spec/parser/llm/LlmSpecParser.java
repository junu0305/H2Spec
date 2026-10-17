package kr.go.h2spec.parser.llm;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmUrlCandidates;
import kr.go.h2spec.parser.llm.validate.ExtractionValidator;
import kr.go.h2spec.parser.llm.validate.ValidationFeedback;
import kr.go.h2spec.parser.llm.validate.ValidationResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * llm 엔진의 {@link SpecParser}. 문서 하나를 아래 순서로 변환한다.
 * <ol>
 *   <li>확장자로 고른 Reader({@link DocumentReaders})로 Block 목록을 읽는다. 교차검증도 같은 목록을 쓴다.</li>
 *   <li>{@link DocumentSerializer}로 직렬화하고, 시스템 프롬프트·문서·작업 지시·스키마로 요청을 만든다({@link ExtractionPrompt}).</li>
 *   <li>결과 캐시({@link ExtractionCache})를 거쳐, 문서 전체를 담은 요청 하나로 회사 어댑터({@link LlmClient})를 부른다.
 *       같은 Block 목록으로 돌린 rule 엔진이 찾은 오퍼레이션이 한 번에 적을 수({@link Options#operationsPerCall()})보다 많거나
 *       출력이 한도에 걸려 잘리면, 오퍼레이션을 나눠 여러 번 부른다.</li>
 *   <li>응답을 중간 스키마로 엄격하게 읽는다. 읽지 못하면 한 번만 다시 부르고, 그래도 못 읽으면 그 문서는 실패다.</li>
 *   <li>원문 대조({@link ExtractionValidator})에서 버린 것이 있으면({@link ValidationFeedback}) 그것을 알려 한 번 더 부르고, 검증을 더 많이 통과한 결과를 쓴다.</li>
 *   <li>정규화({@link IrNormalizer}), 같은 Block 목록으로 돌린 rule 엔진과의 교차검증({@link CrossChecker})을 거친다.</li>
 * </ol>
 * 호출 실패와 쓸 수 없는 응답은 {@link SpecParseException}의 종류로 바꿔 CLI가 처리를 가르게 한다.
 * 키·권한 오류는 {@code AUTH}, 모델 이름·추론 깊이·주소 오류는 {@code SETTINGS}, 잔액·사용 한도 문제는 {@code QUOTA}, 재시도한 뒤에도 남은
 * 호출 한도·서버·네트워크 오류와 다시 부르지 않은 중단된 응답은 {@code RETRYABLE}, 나머지(잘못된 요청, 출력 잘림, 거절,
 * JSON 파싱 실패, 변환한 오퍼레이션 없음)는 {@code FAILED}다. 출력이 잘려도 나눠 불러 이어 가고, 다 적힌 오퍼레이션이 하나도 없을 때만
 * 실패로 알린다.
 * 메시지는 사용자에게 그대로 보여 줄 한 줄이고 API 키를 담지 않는다. 어댑터가 회사의 오류 글에서 키를 가리고, 키를 아는 경우 여기서도 한 번 더 가린다.
 */
public final class LlmSpecParser implements SpecParser {

    /**
     * 출력 토큰 상한의 기본값. thinking(추론) 토큰도 이 안에서 쓰인다.
     * 출력은 오퍼레이션당 약 2천~4천 토큰이고(기상청 중기예보 4개에 1만 6천여 토큰), 한 번에 적는 오퍼레이션 수
     * ({@link #DEFAULT_OPERATIONS_PER_CALL})의 출력이 넉넉히 들어가도록 Claude Opus 5.5·Sonnet 5.5의 출력 한도인 12만 8천으로 둔다.
     * 그래도 잘리면 나눠 불러 이어 가지만 잘린 호출의 출력도 과금된다. 상한일 뿐이라 쓰지 않은 토큰은 과금되지 않고, 어댑터가 스트리밍으로
     * 받아 출력이 길어도 HTTP 타임아웃에 걸리지 않는다. 결과 캐시 키에 들어가므로 바꾸면 같은 문서도 다시 부른다.
     * 출력 한도가 다른 회사의 모델은 그 회사의 어댑터를 더할 때 확인한다.
     */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 128_000;

    /**
     * 나눠 부를 때 한 번에 적게 하는 오퍼레이션 수의 기본값. 오퍼레이션당 출력이 많아야 4천 토큰쯤이라 20개면 8만 토큰 안쪽으로
     * 출력 한도에 넉넉히 들어간다. rule 엔진이 이보다 많은 오퍼레이션을 찾은 문서는 처음부터 나눠 부른다.
     */
    public static final int DEFAULT_OPERATIONS_PER_CALL = 20;

    /**
     * 오퍼레이션 하나에 잡는 출력 토큰. 보이는 출력이 많아야 4천 토큰쯤이고 추론 토큰 몫을 더했다. 출력 한도가 작은 회사는 한 번에 적을
     * 오퍼레이션 수를 이것으로 나눈 수까지 줄인다(Gemini 6만 5천 → 10개). 12만 8천이면 21개라 기본값 20개 그대로다
     */
    private static final int TOKENS_PER_OPERATION = 6_000;

    /** {@link SpecParseResult.Engine#name()}에 적는 엔진 이름 */
    private static final String ENGINE = "llm";
    /** 첫 호출과, JSON으로 읽지 못했을 때의 한 번 더 */
    private static final int MAX_ATTEMPTS = 2;
    /** 문서 하나를 나눠 부르는 호출 수의 상한. 지시를 어긴 응답이 끝없이 이어지지 않게 막는다. 20개씩이면 오퍼레이션 600개다 */
    private static final int MAX_PARTS = 30;

    /**
     * LLM 응답을 중간 스키마로 읽는다. 스키마는 모든 필드를 required로 두고 null을 쓰지 않으므로, 빠진 필드·null·모르는 필드·모르는 enum 값은
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

    /** 잘린 응답을 값 하나씩 트리로 읽는다({@link #salvage}). 스키마 검사는 {@link #STRICT}가 한다 */
    private static final ObjectMapper TREES = new ObjectMapper();

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
     * @param options  출력 한도, 결과 캐시, 교차검증, 다시 부르기, 경고를 받을 곳
     */
    public LlmSpecParser(LlmClient client, String provider, String model, String effort, Options options) {
        this(client, provider, model, effort, options, null, new DocumentSerializer(), LlmSpecParser::runRuleEngine);
    }

    /** {@link #of}가 API 키를 함께 넘길 때 쓴다. 직렬화기와 rule 엔진도 바꿀 수 있게 받는다 */
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
     * @param options  출력 한도, 결과 캐시, 교차검증, 다시 부르기, 경고를 받을 곳. 출력 상한과 한 번에 적을 오퍼레이션 수는 회사 모델의
     *                 출력 한도 안으로 줄인다({@link Options#fittedTo})
     * @return llm 엔진
     */
    public static LlmSpecParser of(LlmProviderFactory provider, LlmSettings settings, Options options) {
        return new LlmSpecParser(provider.create(settings), provider.id(), settings.model(), settings.effort(),
                options.fittedTo(provider.maxOutputTokens()), settings.apiKey(), new DocumentSerializer(),
                LlmSpecParser::runRuleEngine);
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
     * 평가 도구가 쓴다. LLM을 부르지 못했거나 응답을 쓸 수 없으면 똑같이 던진다.
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
        return extract(format.read(document), document.getFileName().toString(), format.sourceFormat());
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
        String document = serialize(blocks, sourceFile);
        // rule 엔진은 교차검증에도 쓰고, 오퍼레이션이 많아 한 번에 적지 못할 문서를 미리 알아보는 데도 쓴다
        RuleRun ruleRun = runRule(blocks, sourceFile, sourceFormat);
        int ruleOperations = ruleRun.result() == null ? 0 : ruleRun.result().operations().size();
        Checked checked = ruleOperations > options.operationsPerCall()
                ? split(document, blocks, sourceFile, null, "rule 엔진이 찾은 오퍼레이션이 " + ruleOperations + "개라")
                : whole(document, blocks, sourceFile);
        List<NormalizedOperation> operations =
                new IrNormalizer(metadata).normalize(checked.validation(), sourceFile, sourceFormat);
        if (options.crossCheck()) {
            operations = crossCheck(operations, ruleRun, sourceFile);
        }
        return result(operations, checked.extraction());
    }

    /**
     * 문서 전체를 한 번에 부른다. 출력이 한도에 걸려 잘리면 다 적힌 오퍼레이션을 건져 그 뒤부터 나눠 부른다.
     * 다 적힌 오퍼레이션이 하나도 없으면 나눠 불러도 같으므로 실패로 알린다.
     */
    private Checked whole(String document, List<Block> blocks, String sourceFile) throws SpecParseException {
        LlmRequest request = prompt.wholeDocumentRequest(document, options.maxOutputTokens());
        Extraction extraction = call(request, sourceFile);
        if (extraction.truncated()) {
            if (extraction.value().operations().isEmpty()) {
                throw truncated(sourceFile, extraction, extraction.fromCache(), extraction.spent(),
                        "다 적힌 오퍼레이션이 없어 나눠 부를 수 없습니다");
            }
            return split(document, blocks, sourceFile, extraction, "출력이 출력 한도에 걸려 잘려");
        }
        return checked(request, extraction, blocks, sourceFile);
    }

    /**
     * 오퍼레이션을 {@link Options#operationsPerCall()}개씩 나눠 부른다. 문서까지의 앞부분은 같고 작업 지시의 범위만 바꾼다
     * ({@link ExtractionPrompt#partRequest}). 새 오퍼레이션이 하나도 오지 않으면 끝낸다. 모델이 한 번에 적은 수가 정한 수보다 적어도
     * 남은 것이 있을 수 있어서, 적은 수로 끝을 판단하지 않는다. 문서 공통 값(서비스명, 제공 기관, 문서 공통 에러코드, 문서 노트)은
     * 첫 응답의 것을 쓴다. 나눠 부른 응답마다 원문 대조에서 버린 것이 있으면 그 응답만 다시 부른다.
     *
     * @param first 문서 전체를 부른 응답이 잘렸으면 그 응답(다 적힌 오퍼레이션만 건진 것). 처음부터 나눠 부르면 null
     * @param why   경고에 붙일 나눠 부른 까닭
     */
    private Checked split(String document, List<Block> blocks, String sourceFile, Extraction first, String why)
            throws SpecParseException {
        List<LlmOperation> done = new ArrayList<>();
        LlmExtraction head = null;
        SpecParseResult.TokenUsage spent = SpecParseResult.TokenUsage.NONE;
        boolean fromCache = true;
        if (first != null) {
            done.addAll(first.value().operations());
            head = first.value();
            spent = first.spent();
            fromCache = first.fromCache();
        }
        int parts = 0;
        while (true) {
            if (parts == MAX_PARTS) {
                warn(sourceFile, "나눠 부른 호출이 " + MAX_PARTS + "번에 닿아 그만 부릅니다. 뒤의 오퍼레이션이 빠졌을 수 있습니다");
                break;
            }
            LlmRequest request = prompt.partRequest(document, done, options.operationsPerCall(), options.maxOutputTokens());
            Extraction part;
            try {
                part = call(request, sourceFile);
            } catch (SpecParseException e) {
                // 앞서 부른 호출(잘린 첫 호출, 앞 파트)도 과금됐으므로 그 토큰을 실패에 담는다
                throw withSpent(e, fromCache, spent);
            }
            parts++;
            Extraction chosen = part.truncated() ? part : checked(request, part, blocks, sourceFile).extraction();
            spent = add(spent, chosen.spent());
            fromCache = fromCache && chosen.fromCache();
            if (head == null) {
                head = chosen.value();
            }
            List<LlmOperation> fresh = newOperations(done, chosen.value().operations());
            done.addAll(fresh);
            if (fresh.isEmpty()) {
                if (part.truncated()) {
                    if (done.isEmpty()) {
                        throw truncated(sourceFile, part, fromCache, spent, "나눠 부른 호출에서도 다 적힌 오퍼레이션이 없어 변환하지 못했습니다");
                    }
                    // 앞서 받은 오퍼레이션은 쓸 수 있으므로 버리지 않는다. 같은 요청은 같은 자리에서 잘릴 것이라 더 부르지 않는다
                    warn(sourceFile, "나눠 부른 호출이 출력 한도 " + options.maxOutputTokens() + " 토큰에 걸려 잘렸고 다 적힌 오퍼레이션이 없어 "
                            + "그만 부릅니다 (" + truncatedDetail(part) + "). 뒤의 오퍼레이션이 빠졌을 수 있습니다");
                }
                break;
            }
        }
        warn(sourceFile, why + " 오퍼레이션을 " + options.operationsPerCall() + "개씩 나눠 불렀습니다 (나눠 부른 호출 " + parts
                + "번, 오퍼레이션 " + done.size() + "개)");
        LlmExtraction merged = new LlmExtraction(head.serviceName(), head.provider(), head.errorCodes(), List.copyOf(done),
                head.documentNotes());
        return new Checked(new Extraction(merged, fromCache, spent, false, 0),
                new ExtractionValidator().validate(merged, blocks));
    }

    /**
     * 앞서 적은 오퍼레이션과 겹치지 않는 것만 고른다. 지시를 어기고 이미 적은 오퍼레이션을 다시 적어도 끝없이 부르지 않게 한다.
     * 영문 오퍼레이션명이 있으면 그것으로, 없으면 국문 기능명과 URL 후보로 같은 오퍼레이션인지 본다.
     */
    private static List<LlmOperation> newOperations(List<LlmOperation> done, List<LlmOperation> operations) {
        Set<String> seen = new HashSet<>();
        done.forEach(operation -> seen.add(identity(operation)));
        List<LlmOperation> fresh = new ArrayList<>();
        for (LlmOperation operation : operations) {
            if (operation != null && seen.add(identity(operation))) {
                fresh.add(operation);
            }
        }
        return fresh;
    }

    private static String identity(LlmOperation operation) {
        String name = operation.operationName() == null ? "" : operation.operationName().strip();
        if (!name.isEmpty()) {
            return "name:" + name;
        }
        LlmUrlCandidates urls = operation.urlCandidates();
        return "title:" + operation.title() + "|" + (urls == null ? "" : urls.callBackUrl() + "|" + urls.tableUrl()
                + "|" + urls.urlLine() + "|" + urls.requestExampleUrl());
    }

    /** 검증하고, 원문 대조에서 버린 것이 있으면 그것을 알려 한 번 더 부른다 */
    private Checked checked(LlmRequest request, Extraction extraction, List<Block> blocks, String sourceFile) {
        ValidationResult validation = new ExtractionValidator().validate(extraction.value(), blocks);
        if (options.retryOnValidation()) {
            List<String> problems = ValidationFeedback.problems(validation);
            if (!problems.isEmpty()) {
                return retry(prompt.retryRequest(request, problems), new Checked(extraction, validation), blocks, sourceFile);
            }
        }
        return new Checked(extraction, validation);
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
            // 다시 부른 호출이 답을 받지 못했으면 과금되지 않았으므로 처음 결과의 캐시 사용 여부를 그대로 둔다
            boolean fromCache = first.extraction().fromCache() && e.engine().isEmpty();
            return new Checked(new Extraction(first.extraction().value(), fromCache, spent, false, 0), first.validation());
        }
        if (again.truncated()) {
            warn(sourceFile, "검증 결과를 알려 다시 부른 응답이 출력 한도에 걸려 잘려 처음 결과를 씁니다");
            SpecParseResult.TokenUsage spent = add(first.extraction().spent(), again.spent());
            boolean fromCache = first.extraction().fromCache() && again.fromCache();
            return new Checked(new Extraction(first.extraction().value(), fromCache, spent, false, 0), first.validation());
        }
        ValidationResult validation = new ExtractionValidator().validate(again.value(), blocks);
        boolean better = ValidationFeedback.better(first.validation(), validation);
        // 앞선 실행에서 같은 재요청을 했으면 캐시에서 꺼내 부르지(과금하지) 않았으므로 그렇게 알린다
        String how = again.fromCache() ? "다시 부른 응답을 결과 캐시에서 꺼냈습니다" : "한 번 더 불렀습니다";
        warn(sourceFile, "원문 대조에서 버린 항목이 있어 검증 결과를 알려 " + how + " (통과한 오퍼레이션 "
                + ValidationFeedback.keptOperations(first.validation()) + "개 → "
                + ValidationFeedback.keptOperations(validation) + "개, " + (better ? "다시 부른 결과를 씀" : "처음 결과를 씀") + ")");
        SpecParseResult.TokenUsage spent = add(first.extraction().spent(), again.spent());
        boolean fromCache = first.extraction().fromCache() && again.fromCache();
        return better
                ? new Checked(new Extraction(again.value(), fromCache, spent, false, 0), validation)
                : new Checked(new Extraction(first.extraction().value(), fromCache, spent, false, 0), first.validation());
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
     * 다음 실행에서도 되풀이되지 않게 한다. 잘린 응답은 저장하고 다 적힌 부분만 건져 돌려준다({@link #salvage}). 같은 요청은 같은
     * 한도에 걸릴 것이라 다음 실행은 다시 부르지(과금하지) 않고 저장한 잘린 글을 쓴다.
     * LLM이 답한 뒤의 실패는 그때까지 쓴 토큰을 예외에 담는다. 그 호출도 과금되기 때문이다.
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
                    remember(key, response, sourceFile);
                }
                return new Extraction(salvage(response.json()), fromCache, spent, true, response.usage().outputTokens());
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
                return new Extraction(extraction, cached.isPresent(), spent, false, 0);
            } catch (JsonProcessingException e) {
                failure = e;
            }
        }
        throw new SpecParseException(SpecParseException.Kind.FAILED, "LLM 응답을 중간 스키마 JSON으로 읽지 못했습니다 ("
                + sourceFile + ", " + MAX_ATTEMPTS + "번 시도): " + describe(failure), failure, engine(fromCache, spent));
    }

    /**
     * 출력 한도에 걸려 잘린 응답에서 다 적힌 부분만 건진다. 최상위 값은 끝까지 적힌 것만, 오퍼레이션은 닫는 괄호까지 적힌 것만
     * 중간 스키마로 읽는다. 끝까지 적혔어도 스키마를 따르지 않는 값을 만나면 거기서 멈춘다. 건지지 못한 값은 빈 값이다.
     */
    static LlmExtraction salvage(String text) {
        String serviceName = "";
        String provider = "";
        List<LlmErrorCode> errorCodes = List.of();
        List<String> documentNotes = List.of();
        List<LlmOperation> operations = new ArrayList<>();
        // 엄격한 매퍼는 값 뒤에 글이 더 있으면 실패하므로, 값 하나씩 읽어 가는 이 일에는 트리만 읽는 매퍼를 쓰고 스키마 검사는 STRICT로 한다
        try (JsonParser parser = TREES.createParser(text)) {
            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String field = parser.currentName();
                    JsonToken value = parser.nextToken();
                    if ("operations".equals(field) && value == JsonToken.START_ARRAY) {
                        while (parser.nextToken() == JsonToken.START_OBJECT) {
                            JsonNode operation = parser.readValueAsTree();
                            operations.add(STRICT.treeToValue(operation, LlmOperation.class));
                        }
                        continue;
                    }
                    JsonNode node = parser.readValueAsTree();
                    switch (field) {
                        case "serviceName" -> serviceName = node.asText("");
                        case "provider" -> provider = node.asText("");
                        case "errorCodes" -> errorCodes = STRICT.readerForListOf(LlmErrorCode.class).readValue(node);
                        case "documentNotes" -> documentNotes = STRICT.readerForListOf(String.class).readValue(node);
                        default -> {
                            // 스키마에 없는 값은 버린다
                        }
                    }
                }
            }
        } catch (IOException | IllegalArgumentException e) {
            // 잘린 자리에 닿았거나 스키마를 따르지 않는 값을 만났다. 그때까지 건진 것만 쓴다
        }
        return new LlmExtraction(serviceName, provider, errorCodes, List.copyOf(operations), documentNotes);
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
     * 어댑터가 이미 재시도한 뒤의 실패다. 종류만 CLI가 쓰는 종류로 옮긴다.
     * 앞서 답을 받은 호출이 있을 때만 엔진 정보를 담는다. 실패한 호출 자체의 사용량은 회사가 알려 주지 않는다.
     */
    private SpecParseException failure(LlmException e, String sourceFile, SpecParseResult.TokenUsage spent) {
        String where = " (" + sourceFile + ", " + provider + ")";
        String detail = e.getMessage() == null || e.getMessage().isBlank() ? "" : ": " + redact(e.getMessage());
        SpecParseResult.Engine used = spent.equals(SpecParseResult.TokenUsage.NONE) ? null : engine(false, spent);
        return switch (e.kind()) {
            case AUTH -> new SpecParseException(SpecParseException.Kind.AUTH,
                    "LLM API 키나 권한 오류로 변환하지 못했습니다" + where + detail, cause(e), used);
            case QUOTA -> new SpecParseException(SpecParseException.Kind.QUOTA,
                    "LLM 계정의 잔액이 없거나 사용 한도를 넘어 변환하지 못했습니다" + where + detail, cause(e), used);
            case SETTINGS -> new SpecParseException(SpecParseException.Kind.SETTINGS,
                    "LLM 설정 오류로 변환하지 못했습니다. 모델 이름(--model), 추론 깊이(--effort), API 주소를 확인하세요"
                            + where + detail,
                    cause(e), used);
            case RATE_LIMITED -> new SpecParseException(SpecParseException.Kind.RETRYABLE,
                    "LLM 호출 한도에 걸려 변환하지 못했습니다. 잠시 뒤 다시 실행하세요" + where + detail, cause(e), used);
            case RETRY_LATER -> new SpecParseException(SpecParseException.Kind.RETRY_LATER,
                    "LLM 회사가 한참 뒤에 다시 부르라고 해 변환하지 못했습니다. 알려 준 시간이 지난 뒤 다시 실행하세요" + where + detail,
                    cause(e), used);
            case UNAVAILABLE -> new SpecParseException(SpecParseException.Kind.RETRYABLE,
                    "LLM 서버나 네트워크 오류로 변환하지 못했습니다. 잠시 뒤 다시 실행하세요" + where + detail, cause(e), used);
            case INTERRUPTED -> new SpecParseException(SpecParseException.Kind.RETRYABLE,
                    "LLM 응답을 끝까지 받지 못해 변환하지 못했습니다. 회사가 끝까지 생성해 과금했을 수 있어 다시 부르지 않았습니다"
                            + where + detail, cause(e), used);
            case BAD_REQUEST -> new SpecParseException(SpecParseException.Kind.FAILED,
                    "LLM이 요청을 받지 않아 변환하지 못했습니다" + where + detail, cause(e), used);
        };
    }

    /**
     * @param part      잘린 응답
     * @param fromCache 이 문서에서 부른 호출이 모두 결과 캐시에서 꺼낸 것인지
     * @param spent     이 문서에서 부른 호출의 토큰 합계
     * @param reason    변환하지 못한 까닭
     */
    private SpecParseException truncated(String sourceFile, Extraction part, boolean fromCache,
                                         SpecParseResult.TokenUsage spent, String reason) {
        return new SpecParseException(SpecParseException.Kind.FAILED, "LLM 출력이 출력 한도 " + options.maxOutputTokens()
                + " 토큰에 걸려 잘렸습니다 (" + sourceFile + ", " + truncatedDetail(part) + "). " + reason,
                null, engine(fromCache, spent));
    }

    /**
     * 잘린 응답의 출력 토큰, 또는 결과 캐시에서 꺼낸 잘린 응답이라 다시 부르지 않았다는 것. thinking 양은 호출마다 달라 다시 부르면 잘리지
     * 않을 수도 있으므로, 캐시에서 꺼냈으면 다시 부르는 방법을 함께 알린다
     */
    private static String truncatedDetail(Extraction part) {
        return part.fromCache()
                ? "앞선 실행에서 같은 요청이 잘려 결과 캐시에 잘린 응답이 있어 다시 부르지 않음. --no-cache로 다시 부를 수 있음"
                : "출력 " + part.truncatedOutputTokens() + " 토큰, thinking 포함";
    }

    /**
     * 나눠 부르는 중 실패한 호출의 예외에, 그 앞에서 부른 호출의 토큰을 더한다.
     *
     * @param fromCache 앞에서 부른 호출이 모두 결과 캐시에서 꺼낸 것인지. 실패한 호출은 실제로 불렀으므로 결과는 캐시 사용이 아니다
     * @param spent     앞에서 부른 호출의 토큰 합계
     */
    private SpecParseException withSpent(SpecParseException e, boolean fromCache, SpecParseResult.TokenUsage spent) {
        if (spent.equals(SpecParseResult.TokenUsage.NONE) && e.engine().isEmpty()) {
            return e;
        }
        SpecParseResult.TokenUsage total = e.engine().map(used -> add(spent, used.tokenUsage())).orElse(spent);
        boolean cacheUsed = fromCache && e.engine().map(SpecParseResult.Engine::cacheUsed).orElse(false);
        return new SpecParseException(e.kind(), e.getMessage(), e.getCause(), engine(cacheUsed, total));
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
     * rule 엔진이 실패해도 LLM 결과는 그대로 쓴다. 교차검증은 사람이 볼 신호를 만드는 단계라 결과를 막지 않는다.
     * rule 엔진만 찾은 오퍼레이션은 오퍼레이션 기록에 넣지 않고 경고로 알린다. 두 엔진의 결과를 한 문서에 섞으면 검수가 어려워진다.
     */
    private List<NormalizedOperation> crossCheck(List<NormalizedOperation> operations, RuleRun ruleRun, String sourceFile) {
        if (ruleRun.result() == null) {
            warn(sourceFile, "rule 엔진이 실패해 교차검증을 건너뜁니다: " + reason(ruleRun.failure()));
            return operations;
        }
        CrossCheckResult checked = new CrossChecker().check(operations, ruleRun.result());
        checked.warnings().forEach(warning -> warn(sourceFile, warning));
        return checked.operations();
    }

    /** rule 엔진이 실패해도 LLM 결과는 그대로 쓴다. 실패는 교차검증할 때 알린다 */
    private RuleRun runRule(List<Block> blocks, String sourceFile, String sourceFormat) {
        try {
            return new RuleRun(rule.assemble(blocks, sourceFile, sourceFormat), null);
        } catch (RuntimeException e) {
            return new RuleRun(null, e);
        }
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

    /** 결과에 담는 엔진 정보. LLM이 답한 뒤의 실패에도 담아 CLI가 과금된 토큰을 알리게 한다 */
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

    /** 교차검증에 쓸 rule 엔진. 실패하는 rule 엔진으로 바꿔 시험할 수 있게 따로 둔다 */
    @FunctionalInterface
    interface RuleRunner {

        RuleEngine.Result assemble(List<Block> blocks, String sourceFile, String sourceFormat);
    }

    /**
     * 읽은 중간 스키마와 그것을 얻은 방법.
     *
     * @param value                 중간 스키마. 잘린 응답이면 다 적힌 부분만 건진 것
     * @param fromCache             결과 캐시에서 꺼냈는지
     * @param spent                 이번 변환에서 실제로 부른 호출의 토큰 사용량 합계. 캐시에서 꺼냈으면 호출하지 않아 0이다
     * @param truncated             출력 한도에 걸려 잘린 응답인지
     * @param truncatedOutputTokens 잘린 응답의 출력 토큰. 잘리지 않았으면 0
     */
    private record Extraction(LlmExtraction value, boolean fromCache, SpecParseResult.TokenUsage spent, boolean truncated,
                              long truncatedOutputTokens) {
    }

    /** rule 엔진의 결과. 실패했으면 결과는 null이고 실패 이유가 있다 */
    private record RuleRun(RuleEngine.Result result, RuntimeException failure) {
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
     * @param warnings        CLI가 표준 오류로 알릴 경고를 받는다. rule 엔진만 찾은 오퍼레이션, 검증 결과를 알려 다시 부른 일,
     *                        교차검증이나 결과 캐시를 쓰지 못한 일이다.
     *                        "파일명: 내용" 꼴의 한 줄이다. {@link SpecParseResult}에는 이것을 담을 자리가 없어 따로 받는다
     * @param retryOnValidation 원문 대조에서 버린 것({@link ValidationFeedback})이 있으면 그것을 알려 한 번 더 부를지.
     *                        다시 부른 문서는 토큰을 두 번 쓴다
     * @param operationsPerCall 나눠 부를 때 한 번에 적게 하는 오퍼레이션 수. rule 엔진이 이보다 많이 찾은 문서는 처음부터 나눠 부른다
     */
    public record Options(int maxOutputTokens, ExtractionCache cache, boolean crossCheck, Consumer<String> warnings,
                          boolean retryOnValidation, int operationsPerCall) {

        /** 출력 한도와 한 번에 적을 오퍼레이션 수는 1 이상이고, 결과 캐시와 경고를 받을 곳은 늘 있다. 끄려면 꺼진 캐시와 버리는 곳을 준다 */
        public Options {
            if (maxOutputTokens < 1) {
                throw new IllegalArgumentException("출력 토큰 상한은 1 이상이어야 합니다: " + maxOutputTokens);
            }
            if (operationsPerCall < 1) {
                throw new IllegalArgumentException("한 번에 적을 오퍼레이션 수는 1 이상이어야 합니다: " + operationsPerCall);
            }
            Objects.requireNonNull(cache, "cache");
            Objects.requireNonNull(warnings, "warnings");
        }

        /**
         * @return 기본 출력 한도, 기본 위치의 결과 캐시, 교차검증 켬, 경고는 버림, 검증 결과를 알려 다시 부르기 켬,
         *         나눠 부를 때 한 번에 오퍼레이션 {@value LlmSpecParser#DEFAULT_OPERATIONS_PER_CALL}개.
         *         경고를 표준 오류로 알리는 것은 CLI의 일이라 라이브러리가 직접 찍지 않는다. CLI는 {@link #withWarnings}로 받을 곳을 준다
         */
        public static Options defaults() {
            Consumer<String> ignore = warning -> { };
            return new Options(DEFAULT_MAX_OUTPUT_TOKENS, ExtractionCache.at(ExtractionCache.defaultDirectory()), true, ignore,
                    true, DEFAULT_OPERATIONS_PER_CALL);
        }

        /**
         * @param tokens 새 출력 토큰 상한
         * @return 출력 한도만 바꾼 설정
         */
        public Options withMaxOutputTokens(int tokens) {
            return new Options(tokens, cache, crossCheck, warnings, retryOnValidation, operationsPerCall);
        }

        /**
         * @param newCache 새 결과 캐시
         * @return 결과 캐시만 바꾼 설정
         */
        public Options withCache(ExtractionCache newCache) {
            return new Options(maxOutputTokens, newCache, crossCheck, warnings, retryOnValidation, operationsPerCall);
        }

        /**
         * @param enabled 교차검증할지
         * @return 교차검증 여부만 바꾼 설정
         */
        public Options withCrossCheck(boolean enabled) {
            return new Options(maxOutputTokens, cache, enabled, warnings, retryOnValidation, operationsPerCall);
        }

        /**
         * @param sink 경고를 받을 곳
         * @return 경고를 받을 곳만 바꾼 설정
         */
        public Options withWarnings(Consumer<String> sink) {
            return new Options(maxOutputTokens, cache, crossCheck, sink, retryOnValidation, operationsPerCall);
        }

        /**
         * @param enabled 원문 대조에서 버린 것을 알려 다시 부를지
         * @return 다시 부르기 여부만 바꾼 설정
         */
        public Options withRetryOnValidation(boolean enabled) {
            return new Options(maxOutputTokens, cache, crossCheck, warnings, enabled, operationsPerCall);
        }

        /**
         * 회사 모델의 출력 한도에 맞춘 설정. 출력 상한은 회사 한도를 넘지 않게 하고, 한 번에 적을 오퍼레이션 수는 그 출력 상한 안에 들어가도록
         * (오퍼레이션당 6천 토큰) 줄인다. 한도보다 크게 보내면 회사가 거절하고, 출력 상한에 들어가지 않는
         * 수를 한 번에 적게 하면 나눠 불러도 매번 잘려 잘린 출력만큼 과금되기 때문이다. 호출자가 출력 상한을 낮췄으면 그 상한에 맞춰 줄인다.
         *
         * @param providerMaxOutputTokens 회사 모델의 출력 토큰 한도
         * @return 맞춘 설정. 출력 상한과 한 번에 적을 오퍼레이션 수가 이미 맞으면 같은 값이다
         */
        public Options fittedTo(int providerMaxOutputTokens) {
            int tokens = Math.min(maxOutputTokens, providerMaxOutputTokens);
            int perCall = Math.min(operationsPerCall, Math.max(1, tokens / TOKENS_PER_OPERATION));
            return new Options(tokens, cache, crossCheck, warnings, retryOnValidation, perCall);
        }

        /**
         * @param count 나눠 부를 때 한 번에 적게 할 오퍼레이션 수
         * @return 그 수만 바꾼 설정
         */
        public Options withOperationsPerCall(int count) {
            return new Options(maxOutputTokens, cache, crossCheck, warnings, retryOnValidation, count);
        }
    }
}
