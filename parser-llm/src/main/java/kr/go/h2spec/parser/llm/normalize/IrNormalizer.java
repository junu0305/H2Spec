package kr.go.h2spec.parser.llm.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.generator.emit.JavaNameRules;
import kr.go.h2spec.parser.IrConventions;
import kr.go.h2spec.parser.llm.schema.LlmAuth;
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmValueType;
import kr.go.h2spec.parser.llm.validate.GroupedNotes;
import kr.go.h2spec.parser.llm.validate.LlmText;
import kr.go.h2spec.parser.llm.validate.NotePrefix;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Field;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Parameter;
import kr.go.h2spec.parser.llm.validate.ValidationResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 검증을 통과한 오퍼레이션을 IR로 바꾼다. IR의 모양은 rule 엔진({@code IrAssembler})과 같고,
 * rule 엔진과 같은 규칙(주소 나누기, apiId, 게이트웨이 serviceKey, 경로 변수, 결과코드 필드, 기본값)은 {@link IrConventions}를
 * 그대로 부른다. 같은 칸에서는 엔진이 달라도 같은 IR이 나와야 생성 클래스명과 파일 경로가 바뀌지 않는다.
 * <p>
 * 지원 범위 밖(GET이 아닌 메서드, 헤더·본문 파라미터, 이름이 serviceKey가 아닌 인증키, 루트가 여럿인 응답)인 오퍼레이션은
 * generator에서 실패하게 두지 않고 이유를 알려 건너뛴다. 다만 버리지는 않는다. 정규화한 IR을 이유와 함께 돌려주어
 * 교차검증과 평가가 "건너뛰기 전"의 추출 결과를 볼 수 있게 한다.
 * <p>
 * {@code reviewNotes}는 출처별로 {@code [LLM]}, {@code [검증]}, {@code [정규화]} 순으로 모은다. {@code [교차검증]}은
 * {@code CrossChecker}가 뒤에 붙인다.
 */
public final class IrNormalizer {

    private static final String GET = "GET";
    /** metadata의 엔진 칸과 거기 적는 이름. rule 엔진은 같은 칸에 rule을 적는다 */
    private static final String ENGINE_KEY = "engine";
    private static final String ENGINE = "llm";
    private static final String SERVICE_KEY_AUTH = "SERVICE_KEY_QUERY_PARAM";
    private static final String XML = "XML";
    private static final String JSON = "JSON";
    /** 공공데이터포털 표준 구조의 루트. 경로가 모두 이 아래면 생성 DTO가 XML과 JSON의 두 모양을 다 읽는다 */
    private static final String STANDARD_ROOT = "response";
    private static final String STRING = "string";
    /** 값이 없음을 뜻하는 표기. rule 엔진도 샘플 칸의 {@code -}를 예시로 옮기지 않는다 */
    private static final String EMPTY_MARK = "-";

    private static final String PATH_REMOVED_NOTE = "주소에 없는 경로 변수 파라미터를 제거함";
    private static final String REQUIRED_UNKNOWN_NOTE = "필수 여부를 알 수 없어 선택(false)으로 둔 요청 파라미터";
    private static final String LEADING_ZERO_NOTE = "앞자리 0이 있는 숫자 값이라 string으로 둔 항목";
    private static final String STANDARD_ASSUMED_NOTE = "응답 구조를 표준 구조로 가정함";
    /**
     * 기본 응답 형식을 모른다고 적은 노트. 형식은 코드가 정하고 필요할 때만 알린다. "포맷 파라미터 없이 부르면 XML이 온다"처럼
     * 기본 형식을 밝힌 문장은 코드가 알리지 않는 내용이라 거르지 않도록, 모른다는 표현만 본다("없이"는 빼고 "없다·없음·없어"는 본다)
     */
    private static final Pattern UNKNOWN_DEFAULT_FORMAT = Pattern.compile("기본 (?:응답 )?(?:형식|포맷)[^.]*"
            + "(?:밝히지 않|밝혀지지 않|밝혀져 있지 않|밝혀 두지 않|알 수 없|적혀 있지 않|나와 있지 않|명시되지 않|명시하지 않"
            + "|정해져 있지 않|없(?!이))");
    private static final String NO_SUCCESS_CODE_NOTE = "문서에서 성공코드를 찾지 못해 00으로 둠";
    private static final String AUTH_SCOPE =
            "generator는 이름이 serviceKey인 쿼리 파라미터만 인증키로 받는다";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmMetadata llm;

    /** @param llm IR {@code metadata.llm}에 적을 실행 설정 */
    public IrNormalizer(LlmMetadata llm) {
        this.llm = llm;
    }

    /**
     * 검증 결과를 오퍼레이션별 IR로 바꾼다.
     *
     * @param validation   검증 결과
     * @param sourceFile   IR metadata에 적을 원본 파일 이름
     * @param sourceFormat IR metadata에 적을 원본 포맷 (DOCX, HWP, HWPX)
     * @return 검증 결과의 오퍼레이션마다 하나 (문서 순서). 건너뛴 오퍼레이션도 이유와 함께 담는다
     */
    public List<NormalizedOperation> normalize(ValidationResult validation, String sourceFile, String sourceFormat) {
        List<NormalizedOperation> result = new ArrayList<>();
        for (ValidatedOperation operation : validation.operations()) {
            result.add(operation.dropped()
                    ? new NormalizedOperation(operation.operationName(), operation.title(), apiId(operation), null,
                    operation.dropReason(), false)
                    : normalize(operation, validation, sourceFile, sourceFormat));
        }
        return result;
    }

    private NormalizedOperation normalize(ValidatedOperation operation, ValidationResult validation,
                                          String sourceFile, String sourceFormat) {
        LlmExtraction extraction = validation.extraction();
        LlmOperation source = operation.source();
        IrConventions.UrlParts parts = operation.address().parts();
        String apiId = parts.apiId();
        List<String> outOfScope = new ArrayList<>();
        // 주소 규칙의 노트는 rule 엔진과 같은 순서(주소 고르기 → 게이트웨이 serviceKey → 겹치는 주소)로 둔다
        GroupedNotes addressNotes = new GroupedNotes(NotePrefix.NORMALIZATION);
        GroupedNotes notes = new GroupedNotes(NotePrefix.NORMALIZATION);
        operation.address().selectionNotes().forEach(addressNotes::add);

        String httpMethod = httpMethod(source.httpMethod(), outOfScope);
        ArrayNode parameters = requestParameters(operation, parts.endpoint(), notes, outOfScope);
        if (IrConventions.needsGatewayServiceKey(operation.address().url(), names(parameters))) {
            // 경로 변수 파라미터보다 먼저 넣어야 경로 변수 다음 자리가 된다 (rule 엔진과 같은 자리)
            IrConventions.insertGatewayServiceKey(parameters);
            addressNotes.add(IrConventions.GATEWAY_SERVICE_KEY_NOTE);
        }
        operation.address().correctionNotes().forEach(addressNotes::add);
        IrConventions.addPathParameters(parameters, parts.endpoint());
        String authType = authType(source.auth(), parameters, outOfScope);
        JsonNode formatParameter = hintedFormatParameter(source, parameters);
        // 형식 노트와 교차검증(formatSelectable)이 같은 기준을 쓴다
        boolean clientSetsFormat = clientSetsFormat(LlmText.oneLine(source.formatParameter()), formatParameter, parameters);
        String responseFormat = responseFormat(operation, clientSetsFormat, names(parameters), notes);
        boolean formatSelectable = source.responseFormat() == LlmOperation.ResponseFormat.BOTH && clientSetsFormat;
        if (source.responseStructure() == null
                || source.responseStructure() == LlmOperation.ResponseStructure.STANDARD_ASSUMED) {
            notes.add(STANDARD_ASSUMED_NOTE);
        }
        ArrayNode fields = responseFields(operation, notes, outOfScope);
        SuccessCodeSelector.Selection success = SuccessCodeSelector.select(operation.successCodes());
        if (success.defaulted()) {
            notes.add(NO_SUCCESS_CODE_NOTE);
        }

        ObjectNode api = objectMapper.createObjectNode();
        api.put("apiId", apiId);
        api.put("apiName", IrConventions.apiName(LlmText.restore(source.title()), apiId));
        api.put("provider", LlmText.oneLine(extraction.provider()));
        api.put("baseUrl", parts.baseUrl());
        api.put("endpoint", parts.endpoint());
        api.put("httpMethod", httpMethod);
        api.put("description", LlmText.oneLine(source.description()));
        api.put("authType", authType);
        api.put("responseFormat", responseFormat);
        api.set("requestParameters", parameters);
        api.set("responseFields", fields);
        api.set("errorSpec", errorSpec(operation, validation.errorCodes(), success.code()));

        List<String> reviewNotes = new ArrayList<>();
        boolean standardAssumed = source.responseStructure() == null
                || source.responseStructure() == LlmOperation.ResponseStructure.STANDARD_ASSUMED;
        for (List<String> llmNotes : List.of(list(source.uncertain()), list(extraction.documentNotes()))) {
            llmNotes.stream()
                    .filter(text -> !repeatsCodeNote(LlmText.oneLine(text), standardAssumed, success, operation))
                    .forEach(text -> addLlmNote(reviewNotes, text));
        }
        list(source.unsupported()).forEach(text -> addLlmNote(reviewNotes, text));
        reviewNotes.addAll(operation.notes());
        if (success.note() != null) {
            reviewNotes.add(NotePrefix.VALIDATION.note(success.note()));
        }
        reviewNotes.addAll(addressNotes.lines());
        reviewNotes.addAll(notes.lines());

        ObjectNode ir = objectMapper.createObjectNode();
        ir.set("metadata", metadata(sourceFile, sourceFormat, reviewNotes));
        ir.set("api", api);
        ObjectNode hints = IrConventions.generatorHints(apiId);
        if (formatParameter != null) {
            ObjectNode hint = hints.putObject("responseFormatParameter");
            hint.put("name", formatParameter.get("name").asText());
            hint.put("value", formatValue(formatParameter, responseFormat));
        }
        ir.set("generatorHints", hints);
        String skipReason = outOfScope.isEmpty() ? null : String.join("; ", outOfScope);
        return new NormalizedOperation(operation.operationName(), operation.title(), apiId, ir, skipReason,
                skipReason != null, formatSelectable);
    }

    /**
     * 메서드를 오퍼레이션마다 적은 문서는 거의 없고 rule 엔진도 늘 GET으로 가정하므로, 표기가 없어도 노트 없이 GET이다.
     * GET 없이 다른 메서드만 밝힌 경우만 지원 범위 밖이다.
     */
    private String httpMethod(LlmOperation.HttpMethod method, List<String> outOfScope) {
        if (method == LlmOperation.HttpMethod.POST || method == LlmOperation.HttpMethod.OTHER) {
            outOfScope.add("GET이 아닌 메서드만 밝힌 오퍼레이션 (" + method + "): generator v1은 GET만 받는다");
            return method.name();
        }
        return GET;
    }

    // ---- 요청 파라미터 ----

    private ArrayNode requestParameters(ValidatedOperation operation, String endpoint, GroupedNotes notes,
                                        List<String> outOfScope) {
        ArrayNode parameters = objectMapper.createArrayNode();
        List<String> header = new ArrayList<>();
        List<String> body = new ArrayList<>();
        for (Parameter parameter : operation.requestParameters()) {
            LlmRequestParameter source = parameter.source();
            String name = parameter.name();
            LlmRequestParameter.Location location = source.location() == null
                    ? LlmRequestParameter.Location.QUERY
                    : source.location();
            if (location == LlmRequestParameter.Location.PATH && !endpoint.contains("{" + name + "}")) {
                // generator는 endpoint에 없는 path 파라미터를 받지 않는다 (IrLoader)
                notes.add(PATH_REMOVED_NOTE, name);
                continue;
            }
            if (location == LlmRequestParameter.Location.HEADER) {
                header.add(name);
            } else if (location == LlmRequestParameter.Location.BODY) {
                body.add(name);
            }
            String example = value(source.example());
            String defaultValue = value(source.defaultValue());
            String type = type(source.type());
            if (isNumeric(type) && (IrConventions.hasLeadingZero(example) || IrConventions.hasLeadingZero(defaultValue))) {
                // rule 엔진과 같은 정규식이다. rule 엔진은 노트를 남기지 않지만 LLM이 정한 타입을 바꾸는 것이라 알린다
                type = STRING;
                notes.add(LEADING_ZERO_NOTE, name);
            }
            ObjectNode node = parameters.addObject();
            node.put("name", name);
            node.put("in", location.name().toLowerCase(Locale.ROOT));
            node.put("type", type);
            // 주소의 경로 변수와 이름이 같으면 뒤에서 경로 변수(필수)가 되므로 필수 여부를 몰라도 알리지 않는다
            boolean pathVariable = location == LlmRequestParameter.Location.PATH
                    || IrConventions.pathVariableNames(endpoint).contains(name);
            node.put("required", required(source.required(), pathVariable, name, notes));
            node.put("description", LlmText.oneLine(source.description()));
            if (!example.isEmpty()) {
                node.put("example", example);
            }
            if (!defaultValue.isEmpty()) {
                node.put("default", defaultValue);
            }
        }
        if (!header.isEmpty() || !body.isEmpty()) {
            List<String> where = new ArrayList<>();
            if (!header.isEmpty()) {
                where.add("헤더: " + String.join(", ", header));
            }
            if (!body.isEmpty()) {
                where.add("본문: " + String.join(", ", body));
            }
            outOfScope.add("요청 파라미터 위치 미지원 (" + String.join(" / ", where)
                    + "): generator는 query·path 파라미터만 받는다");
        }
        return parameters;
    }

    /** 경로 변수는 늘 필수다. 표기가 없는 파라미터는 선택으로 두고 알린다 */
    private boolean required(LlmRequestParameter.Requirement requirement, boolean pathVariable, String name,
                             GroupedNotes notes) {
        if (pathVariable || requirement == LlmRequestParameter.Requirement.REQUIRED) {
            return true;
        }
        if (requirement != LlmRequestParameter.Requirement.OPTIONAL) {
            notes.add(REQUIRED_UNKNOWN_NOTE, name);
        }
        return false;
    }

    /**
     * 인증 방식. 게이트웨이 serviceKey는 부르기 전에 보충했다. 이름이 serviceKey(대소문자 무시, KASI 문서는
     * {@code ServiceKey})인 query 파라미터가 있어야 받는다. generator는 {@code authType}을 읽지 않고 serviceKey라는 이름만
     * 찾기 때문이다({@code ClientEmitter}). 그 밖의 인증은 generator가 받지 못해 지원 범위 밖이다.
     */
    // TODO(인증 확장): generator가 다른 인증을 받게 되면 여기서 지원 범위 밖으로 건너뛰지 않고 IR에 인증 정보를 적는다.
    //   자세한 내용은 generator의 ClientEmitter.findServiceKeyParam
    private String authType(LlmAuth auth, ArrayNode parameters, List<String> outOfScope) {
        for (JsonNode parameter : parameters) {
            if ("query".equals(parameter.get("in").asText())
                    && IrConventions.SERVICE_KEY.equalsIgnoreCase(parameter.get("name").asText())) {
                return SERVICE_KEY_AUTH;
            }
        }
        outOfScope.add("인증 방식 미지원 (" + authDetail(auth) + "): " + AUTH_SCOPE);
        return auth == null || auth.kind() == null ? LlmAuth.Kind.UNKNOWN.name() : auth.kind().name();
    }

    private static String authDetail(LlmAuth auth) {
        LlmAuth.Kind kind = auth == null || auth.kind() == null ? LlmAuth.Kind.UNKNOWN : auth.kind();
        String name = auth == null ? "" : LlmText.oneLine(auth.parameterName());
        return switch (kind) {
            case PATH_KEY -> name.isEmpty() ? "주소 경로의 인증키" : "주소 경로의 {" + name + "}";
            case HEADER_KEY -> name.isEmpty() ? "헤더 인증" : "헤더의 " + name;
            case SERVICE_KEY_QUERY -> name.isEmpty() ? "이름이 serviceKey인 쿼리 파라미터 없음" : "쿼리 파라미터 " + name;
            case NONE -> "인증키 없음";
            case OTHER -> name.isEmpty() ? "그 밖의 인증 방식" : "그 밖의 인증 방식 " + name;
            case UNKNOWN -> "인증 방식을 알 수 없고 serviceKey 쿼리 파라미터 없음";
        };
    }

    // ---- 응답 ----

    /**
     * IR의 응답 포맷. 문서가 한 형식만 밝혔으면 그 형식이고, 두 형식을 다 내면 포맷 파라미터 없이 부를 때의 형식(기본 형식)이다.
     * 기본 형식을 모르거나 형식이 적혀 있지 않으면 응답 경로를 정한 예제의 형식을 쓴다. 경로와 다른 형식으로 두면 생성 DTO가
     * 응답을 읽지 못할 수 있기 때문이다. 예제도 없으면 XML이다(rule 엔진도 형식을 모르면 XML이다).
     * 생성 클라이언트가 포맷 파라미터에 값을 채우지 못하면 서버 기본 형식이 오므로, 그 기본 형식을 모르면 알린다.
     * 문서가 정한 형식과 경로를 정한 예제의 형식이 다르면, 표준 구조가 아닌 한 경로가 맞는지 알린다.
     *
     * @param clientSetsFormat 생성 클라이언트가 포맷 파라미터에 값을 채우는지. generator가 아는 이름이거나 generatorHints로 알린 이름이다
     * @param parameterNames   IR의 요청 파라미터 이름
     */
    private String responseFormat(ValidatedOperation operation, boolean clientSetsFormat, List<String> parameterNames,
                                  GroupedNotes notes) {
        LlmOperation source = operation.source();
        LlmOperation.ResponseFormat format = source.responseFormat() == null
                ? LlmOperation.ResponseFormat.UNKNOWN
                : source.responseFormat();
        String example = exampleFormat(source);
        String chosen;
        switch (format) {
            case XML -> chosen = XML;
            case JSON -> chosen = JSON;
            case BOTH -> {
                LlmOperation.DefaultResponseFormat defaultFormat = source.defaultResponseFormat();
                if (defaultFormat == LlmOperation.DefaultResponseFormat.XML) {
                    chosen = XML;
                } else if (defaultFormat == LlmOperation.DefaultResponseFormat.JSON) {
                    chosen = JSON;
                } else {
                    chosen = example == null ? XML : example;
                    String parameter = LlmText.oneLine(source.formatParameter());
                    if (!clientSetsFormat) {
                        String why;
                        if (parameter.isEmpty()) {
                            why = "응답 형식을 고르는 파라미터가 없고";
                        } else if (!parameterNames.contains(parameter)) {
                            why = "포맷 파라미터 " + parameter + "가 요청 파라미터에 없고";
                        } else {
                            why = "포맷 파라미터 " + parameter + "는 generator가 값을 채우지 않고";
                        }
                        notes.add(why + " 기본 응답 형식도 알 수 없어 " + chosenBy(example) + " (서버 기본 형식 확인 필요)");
                    }
                }
            }
            default -> {
                chosen = example == null ? XML : example;
                notes.add(example == null ? "응답 형식을 알 수 없어 XML로 둠" : "응답 형식이 적혀 있지 않아 " + chosenBy(example));
            }
        }
        if (example != null && !example.equals(chosen) && !standardPaths(operation)) {
            notes.add("응답 형식은 " + chosen + "인데 응답 경로는 " + example + " 예제를 따름 ("
                    + chosen + " 응답의 구조와 같은지 확인 필요)");
        }
        return chosen;
    }

    /** 응답 경로를 정한 예제의 형식. 예제로 구조를 정하지 않았으면 null */
    private static String exampleFormat(LlmOperation source) {
        LlmOperation.ResponseExampleFormat format = source.responseExampleFormat();
        if (source.responseStructure() != LlmOperation.ResponseStructure.EXAMPLE
                || format == null || format == LlmOperation.ResponseExampleFormat.NONE) {
            return null;
        }
        return format.name();
    }

    /** 형식을 정한 까닭을 노트 글로 적는다 ("XML로 둠", "응답 경로를 정한 JSON 예제를 따라 JSON으로 둠") */
    private static String chosenBy(String example) {
        return example == null ? "XML로 둠" : "응답 경로를 정한 " + example + " 예제를 따라 " + withTo(example) + " 둠";
    }

    /** 형식 이름에 조사 "(으)로"를 붙인다. XML은 받침이 ㄹ이라 "로", JSON은 "으로"다 */
    private static String withTo(String format) {
        return format + (JSON.equals(format) ? "으로" : "로");
    }

    /** 경로가 모두 표준 구조의 루트 아래에 있으면 생성 DTO가 XML과 JSON의 두 모양을 다 읽는다 ({@code DtoEmitter}) */
    private static boolean standardPaths(ValidatedOperation operation) {
        return operation.responseFields().stream().allMatch(field -> STANDARD_ROOT.equals(field.root()));
    }

    /**
     * generatorHints로 알려 줄 포맷 파라미터. LLM이 고른 포맷 파라미터가 IR의 쿼리 파라미터이고 generator가 이미 아는
     * 이름({@code returnType}, {@code dataType}, {@code resultType})이 아닐 때만 돌려준다. 없으면 null
     */
    private static JsonNode hintedFormatParameter(LlmOperation source, ArrayNode parameters) {
        String name = LlmText.oneLine(source.formatParameter());
        if (name.isEmpty() || IrConventions.responseFormatParameters().contains(name)) {
            return null;
        }
        for (JsonNode parameter : parameters) {
            if (name.equals(parameter.get("name").asText()) && "query".equals(parameter.get("in").asText())) {
                return parameter;
            }
        }
        return null;
    }

    /** 포맷 파라미터에 넣을 값. 예제나 기본값에 적힌 형식 이름이 대문자이면 대문자로, 아니면 소문자로 넣는다 */
    private static String formatValue(JsonNode parameter, String responseFormat) {
        for (String field : List.of("example", "default")) {
            String value = parameter.path(field).asText("");
            if (value.equalsIgnoreCase(XML) || value.equalsIgnoreCase(JSON)) {
                return value.equals(value.toUpperCase(Locale.ROOT))
                        ? responseFormat
                        : responseFormat.toLowerCase(Locale.ROOT);
            }
        }
        return responseFormat.toLowerCase(Locale.ROOT);
    }

    /**
     * 생성 클라이언트가 IR의 응답 형식을 포맷 파라미터 값으로 채워 보내는지. LLM이 포맷 파라미터를 밝혔으면 그 파라미터를 채우는지만 본다.
     * 문서의 포맷 파라미터가 IR에 없으면 다른 이름의 파라미터를 채워도 형식이 골라지지 않는다. 밝히지 않았으면 generator가 아는 이름의
     * 파라미터가 있는지 본다(rule 엔진의 IR과 같은 기준).
     *
     * @param formatParameter LLM이 밝힌 포맷 파라미터 이름. 없으면 빈 문자열
     * @param hinted          generatorHints로 알릴 포맷 파라미터. 없으면 null
     */
    private static boolean clientSetsFormat(String formatParameter, JsonNode hinted, ArrayNode parameters) {
        if (hinted != null) {
            return true;
        }
        if (formatParameter.isEmpty()) {
            return hasGeneratorFormatParameter(parameters);
        }
        return IrConventions.responseFormatParameters().contains(formatParameter) && names(parameters).contains(formatParameter);
    }

    /** 생성 클라이언트가 IR의 응답 형식을 값으로 채워 보내는 포맷 파라미터가 있는지 ({@code ClientEmitter}) */
    private static boolean hasGeneratorFormatParameter(ArrayNode parameters) {
        for (JsonNode parameter : parameters) {
            if (IrConventions.responseFormatParameters().contains(parameter.get("name").asText())) {
                return true;
            }
        }
        return false;
    }

    private ArrayNode responseFields(ValidatedOperation operation, GroupedNotes notes, List<String> outOfScope) {
        String fieldName = operation.resultCodeField();
        String fieldLeaf = fieldName.substring(fieldName.lastIndexOf('.') + 1);
        ArrayNode fields = objectMapper.createArrayNode();
        Set<String> roots = new LinkedHashSet<>();
        for (Field field : operation.responseFields()) {
            roots.add(field.root());
            String leaf = field.leaf();
            boolean resultCode = IrConventions.isResultCodeField(leaf) || leaf.equals(fieldLeaf);
            String type = type(field.source().type());
            if (resultCode || IrConventions.isResultCodeOrMessageField(leaf)) {
                // 결과코드·결과메시지는 샘플이 숫자("00")여도 앞자리 0을 지키려고 늘 문자열이다 (rule 엔진과 같다)
                type = STRING;
            } else if (isNumeric(type) && IrConventions.hasLeadingZero(value(field.source().sample()))) {
                type = STRING;
                notes.add(LEADING_ZERO_NOTE, leaf);
            }
            ObjectNode node = fields.addObject();
            node.put("path", field.path());
            node.put("type", type);
            node.put("description", LlmText.oneLine(field.source().description()));
            if (resultCode) {
                // generator는 읽기만 하고 아직 쓰지 않는다
                node.put("isResultIndicator", true);
            }
        }
        // TODO(루트 여럿): generator가 루트 없는 응답을 받게 되면 건너뛰지 않는다. 자세한 내용은 generator의 DtoTreeBuilder.build
        if (roots.size() > 1) {
            outOfScope.add("응답 루트가 둘 이상 (" + String.join(", ", roots)
                    + "): generator는 루트 하나로 감싼 응답만 받는다");
        }
        return fields;
    }

    /**
     * 에러코드는 오퍼레이션에만 있는 표를 먼저, 문서 공통 표를 뒤에 둔다. 같은 코드는 앞의 것만 남긴다.
     */
    private ObjectNode errorSpec(ValidatedOperation operation, List<LlmErrorCode> documentErrorCodes,
                                 String successCode) {
        ObjectNode errorSpec = IrConventions.errorSpec();
        errorSpec.put("successResultCode", successCode);
        String fieldName = operation.resultCodeField();
        ArrayNode candidates = (ArrayNode) errorSpec.get("resultCodeFieldCandidates");
        if (!fieldName.isEmpty() && JavaNameRules.isSafeRawName(fieldName) && !contains(candidates, fieldName)) {
            // 기본 목록에 없는 결과코드 위치만 앞에 더한다. generator가 마지막 마디를 결과코드 이름으로 인터셉터에 넘긴다
            candidates.insert(0, fieldName);
        }
        List<LlmErrorCode> errors = new ArrayList<>(operation.errorCodes());
        errors.addAll(documentErrorCodes);
        Set<String> codes = new HashSet<>();
        errors.removeIf(error -> !codes.add(error.code()));
        if (!errors.isEmpty()) {
            ArrayNode known = errorSpec.putArray("knownErrorCodes");
            for (LlmErrorCode error : errors) {
                ObjectNode node = known.addObject();
                node.put("code", error.code());
                node.put("message", LlmText.oneLine(error.message()));
            }
        }
        return errorSpec;
    }

    // ---- metadata ----

    /** rule 엔진과 같은 metadata에서 엔진을 llm으로 바꾸고 바로 뒤에 실행 설정을 더한다 */
    private ObjectNode metadata(String sourceFile, String sourceFormat, List<String> reviewNotes) {
        ObjectNode metadata = objectMapper.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields =
                IrConventions.metadata(sourceFile, sourceFormat, reviewNotes).fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (field.getKey().equals(ENGINE_KEY)) {
                metadata.put(ENGINE_KEY, ENGINE);
                metadata.set("llm", llm.toJson());
            } else {
                metadata.set(field.getKey(), field.getValue());
            }
        }
        return metadata;
    }

    /**
     * 코드가 {@code [검증]}·{@code [정규화]} 노트로 따로 알리는 것을 LLM이 다시 적은 노트인지 본다. 프롬프트가 적지 말라고 해도 가끔 적어
     * 같은 말이 두 번 붙는다. 겹치는 것이 분명한 세 경우만 거른다: 표준 구조를 가정한 오퍼레이션의 표준 구조 이야기, 기본 응답 형식을 모른다는
     * 이야기, 성공코드 후보가 엇갈린 오퍼레이션에서 그 후보 값을 둘 이상 그대로 적은 이야기.
     */
    private static boolean repeatsCodeNote(String text, boolean standardAssumed, SuccessCodeSelector.Selection success,
                                           ValidatedOperation operation) {
        if (standardAssumed && text.contains("표준 구조")) {
            return true;
        }
        if (UNKNOWN_DEFAULT_FORMAT.matcher(text).find()) {
            return true;
        }
        if (success.note() == null) {
            return false;
        }
        long mentioned = operation.successCodes().stream()
                .map(ValidatedOperation.SuccessCode::code)
                .distinct()
                .filter(code -> Pattern.compile("(?<![A-Za-z0-9_-])" + Pattern.quote(code) + "(?![A-Za-z0-9_-])")
                        .matcher(text).find())
                .count();
        return mentioned >= 2;
    }

    private static void addLlmNote(List<String> notes, String text) {
        String line = LlmText.oneLine(text);
        if (!line.isEmpty()) {
            notes.add(NotePrefix.LLM.note(line));
        }
    }

    // ---- 값 ----

    /** 예시·기본값·샘플. 빈 값과 {@code -}는 넣지 않는다 */
    private static String value(String text) {
        String value = LlmText.oneLine(text);
        return EMPTY_MARK.equals(value) ? "" : value;
    }

    /** IR의 타입은 소문자다. 스키마는 타입을 비우지 못하게 하지만, 없으면 가장 안전한 string으로 둔다 */
    private static String type(LlmValueType type) {
        return type == null ? STRING : type.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isNumeric(String type) {
        return type.equals("integer") || type.equals("number");
    }

    /** 주소를 나눈 뒤에 건너뛴 오퍼레이션(V2·V11)은 apiId를 알린다. 같은 apiId로 건너뛴 이유를 읽는 데 쓴다 */
    private static String apiId(ValidatedOperation operation) {
        IrConventions.UrlParts parts = operation.address() == null ? null : operation.address().parts();
        return parts == null ? null : parts.apiId();
    }

    private static List<String> names(ArrayNode parameters) {
        List<String> names = new ArrayList<>();
        parameters.forEach(parameter -> names.add(parameter.get("name").asText()));
        return names;
    }

    private static boolean contains(ArrayNode values, String value) {
        for (JsonNode node : values) {
            if (node.asText().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }
}
