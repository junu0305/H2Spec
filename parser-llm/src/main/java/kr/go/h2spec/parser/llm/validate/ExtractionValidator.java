package kr.go.h2spec.parser.llm.validate;

import kr.go.h2spec.generator.emit.JavaNameRules;
import kr.go.h2spec.parser.Block;
import kr.go.h2spec.parser.IrConventions;
import kr.go.h2spec.parser.UrlCandidates;
import kr.go.h2spec.parser.llm.schema.LlmErrorCode;
import kr.go.h2spec.parser.llm.schema.LlmExtraction;
import kr.go.h2spec.parser.llm.schema.LlmOperation;
import kr.go.h2spec.parser.llm.schema.LlmOperation.ResponseStructure;
import kr.go.h2spec.parser.llm.schema.LlmRequestParameter;
import kr.go.h2spec.parser.llm.schema.LlmResponseField;
import kr.go.h2spec.parser.llm.schema.LlmResultCode;
import kr.go.h2spec.parser.llm.schema.LlmSuccessCode;
import kr.go.h2spec.parser.llm.schema.LlmUrlCandidates;
import kr.go.h2spec.parser.llm.validate.SourceText.Hit;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Address;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Field;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Parameter;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.Removal;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.SuccessCode;
import kr.go.h2spec.parser.llm.validate.ValidatedOperation.UrlSources;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM 출력을 문서 원문과 대조하고 형식을 검사한다 (검증 규칙 V1~V12).
 * <p>
 * 순서는 V1(주소 후보·오퍼레이션명) → 주소 고르기·겹치는 주소 바로잡기 → V2·V11이다. V2와 V11은 주소를 고른 뒤에야 볼 수 있다.
 * 나머지 검사는 오퍼레이션마다 따로 한다. 다만 겹치는 주소 바로잡기에는 rule 엔진처럼 주소를 정하고 응답 필드가 남은(V10)
 * 오퍼레이션만 넣는다. rule 엔진은 응답 필드 표가 없는 오퍼레이션을 바로잡기 전에 건너뛰므로, 같은 후보에서 같은 주소가 나오려면
 * 바로잡기에 넣는 오퍼레이션도 같아야 한다.
 * <p>
 * 원문 대조는 지어낸 값을 거르는 장치일 뿐 정답을 보증하지 않는다. {@code no}, {@code id}처럼 짧은 이름은 다른 칸에
 * 우연히 있을 수 있다. 이런 오류는 교차검증({@code CrossChecker})과 평가로 잡는다.
 * DTO 중첩 클래스 이름 충돌처럼 generator만 아는 규칙은 흉내 내지 않는다.
 */
public final class ExtractionValidator {

    /** 성공코드 후보의 꼴 (V8) */
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]+");
    /** 나눈 baseUrl은 스킴과 호스트를 담아야 한다 (V2) */
    private static final Pattern HOST_BASE_URL = Pattern.compile("(?i)https?://[^/]+.*");
    private static final String LIST_SUFFIX = "[]";
    /** 값이 비어 있음을 뜻하는 표기. Call Back URL 칸을 비워 두는 대신 적는 문서가 있다 */
    private static final String NOT_AVAILABLE = "N/A";
    /** 표준 구조를 가정했을 때 값 필드 앞에 올 수 있는 컨테이너 칸 (V5) */
    private static final List<List<String>> STANDARD_CONTAINERS = List.of(
            List.of("response", "header"),
            List.of("response", "body"),
            List.of("response", "body", "items", "item[]"));
    /** 원래 이름에 넣을 수 없는 따옴표. 큰따옴표·역슬래시·제어문자는 generator의 규칙이 따로 거른다 */
    private static final String OTHER_QUOTES = "'“”‘’";

    private static final String V3_NOTE = "원문에서 찾지 못한 요청 파라미터를 제거함";
    private static final String V4_NOTE = "생성 코드에 쓸 수 없는 이름의 요청 파라미터를 제거함";
    private static final String V6_NOTE = "이름 규칙에 맞지 않는 응답 경로를 제거함";
    private static final String V7_LEAF_NOTE = "앞 필드와 경로 트리가 충돌하는(값 필드이면서 컨테이너) 응답 필드를 제거함";
    private static final String V7_LIST_NOTE = "앞 필드와 [] 표기가 다른 응답 필드를 제거함";
    private static final String V11_URL_NOTE = "주소가 같은 뒤 오퍼레이션을 건너뜀";
    private static final String V11_API_ID_NOTE = "apiId가 같은 뒤 오퍼레이션을 건너뜀";
    private static final String V12_PARAMETER_NOTE = "Java 이름이 앞 파라미터와 겹치는 요청 파라미터를 제거함";
    private static final String V12_FIELD_NOTE = "Java 이름이 같은 자리의 앞 필드와 겹치는 응답 필드를 제거함";
    private static final String RESULT_FIELD_NOT_FOUND_NOTE = "원문에서 찾지 못한 결과코드 필드 이름을 버림";
    private static final String RESULT_MESSAGE_FIELD_NOTE = "결과메시지 필드라 결과코드 필드로 쓰지 않음";
    private static final String URL_NOT_FIRST_NOTE = "주소 후보를 찾은 칸에 다른 주소가 먼저 있어 rule 엔진처럼 칸의 첫 주소를 씀";

    /** V5에서 응답 경로를 버린 이유. 노트는 이유마다 한 줄로 묶는다 */
    private enum PathFailure {
        LEAF_NOT_IN_TABLE("마지막 칸을 응답 필드 표에서 찾지 못한 응답 필드를 제거함"),
        LEAF_NOT_IN_EXAMPLE("마지막 칸을 응답 예제의 키·태그에서 찾지 못한 응답 필드를 제거함"),
        CONTAINER_NOT_IN_EXAMPLE("컨테이너 칸을 응답 예제의 키·태그에서 찾지 못한 응답 필드를 제거함"),
        CONTAINER_NOT_IN_TABLE("컨테이너 칸을 표에서 찾지 못한 응답 필드를 제거함"),
        NOT_STANDARD("표준 구조로 가정했지만 표준 골격이 아닌 응답 경로를 제거함");

        private final String note;

        PathFailure(String note) {
            this.note = note;
        }
    }

    /**
     * LLM 출력을 원문과 대조한다.
     *
     * @param extraction LLM이 돌려준 추출 결과
     * @param blocks     같은 문서를 Reader로 읽은 블록 목록. LLM에 보낸 직렬화 텍스트의 원본이다
     * @return 오퍼레이션마다의 검증 결과 (LLM 결과의 순서)
     */
    public ValidationResult validate(LlmExtraction extraction, List<Block> blocks) {
        SourceText source = new SourceText(blocks);
        List<LlmOperation> operations = list(extraction.operations());
        List<Draft> drafts = new ArrayList<>();
        for (int i = 0; i < operations.size(); i++) {
            drafts.add(check(i, operations.get(i), source));
        }
        correctSharedUrls(drafts);
        drafts.forEach(this::checkUrl);
        dropDuplicates(drafts);
        return new ValidationResult(extraction, drafts.stream().map(Draft::result).toList(),
                checkErrorCodes(extraction.errorCodes(), source, Set.of(), null));
    }

    /** 주소를 고르는 데까지와 오퍼레이션마다 따로 보는 검사 */
    private Draft check(int position, LlmOperation operation, SourceText source) {
        Draft draft = new Draft(position, operation);
        Set<Integer> evidence = source.evidenceBlocks(operation.evidence());
        checkUrlCandidates(draft, source, evidence);
        draft.url = IrConventions.endpointUrl(draft.candidates, draft.selectionNotes);
        draft.parameters = checkParameters(draft, source, evidence);
        draft.fields = checkFields(draft, source, evidence);
        if (draft.fields.isEmpty()) {
            draft.drop("검증을 통과한 응답 필드가 없습니다");
        }
        draft.resultCodeField = checkResultCodeField(draft, source, evidence);
        draft.successCodes = checkSuccessCodes(draft, source, evidence);
        draft.errorCodes = checkErrorCodes(draft.operation.errorCodes(), source, evidence, draft);
        return draft;
    }

    // ---- V1: 주소 후보, 영문 오퍼레이션명 ----

    private void checkUrlCandidates(Draft draft, SourceText source, Set<Integer> evidence) {
        LlmUrlCandidates urls = draft.operation.urlCandidates() == null
                ? new LlmUrlCandidates("", "", "", "", "")
                : draft.operation.urlCandidates();
        // 칸마다 rule 엔진이 그 칸에 쓰는 함수로 주소를 꺼낸다
        String callBack = locateUrl(draft, source, evidence, "callBackUrl", urls.callBackUrl(),
                text -> IrConventions.callBackUrl(text) != null || IrConventions.schemelessUrl(text) != null);
        String table = locateUrl(draft, source, evidence, "tableUrl", urls.tableUrl(),
                text -> IrConventions.firstUrl(text) != null);
        String line = locateUrl(draft, source, evidence, "urlLine", urls.urlLine(),
                text -> IrConventions.firstUrl(text) != null);
        String example = locateUrl(draft, source, evidence, "requestExampleUrl", urls.requestExampleUrl(),
                text -> IrConventions.firstUrl(text) != null);
        String service = locateUrl(draft, source, evidence, "serviceUrl", urls.serviceUrl(),
                text -> IrConventions.serviceUrl(text) != null);
        draft.operationName = locateOperationName(draft, source, evidence);
        draft.sources = new UrlSources(callBack, table, line, example, service);
        draft.candidates = new UrlCandidates(
                callBack == null ? null : IrConventions.callBackUrl(callBack),
                callBack == null ? null : IrConventions.schemelessUrl(callBack),
                table == null ? null : IrConventions.firstUrl(table),
                line == null ? null : IrConventions.firstUrl(line),
                example == null ? null : IrConventions.firstUrl(example),
                service == null ? null : IrConventions.serviceUrl(service),
                draft.operationName,
                // llm 엔진은 공통 구간을 오퍼레이션으로 내지 않는다
                false);
        UrlCandidates candidates = draft.candidates;
        noteOtherUrl(draft, "callBackUrl", urls.callBackUrl(),
                candidates.callBackUrl() != null ? candidates.callBackUrl() : candidates.schemelessCallBackUrl());
        noteOtherUrl(draft, "tableUrl", urls.tableUrl(), candidates.tableUrl());
        noteOtherUrl(draft, "urlLine", urls.urlLine(), candidates.lineUrl());
        noteOtherUrl(draft, "requestExampleUrl", urls.requestExampleUrl(), candidates.exampleUrl());
        noteOtherUrl(draft, "serviceUrl", urls.serviceUrl(), candidates.serviceUrl());
    }

    /**
     * 칸에서 꺼낸 주소가 LLM이 가리킨 주소와 다르면 알린다. 주소는 같은 칸에서 rule 엔진과 같은 값이 나오도록 칸의 원래 글에서 rule 엔진의
     * 함수로 꺼내므로, 한 칸에 주소가 여럿이면(개발·운영 주소, 여러 오퍼레이션의 요청 예제) LLM이 가리킨 주소 대신 칸의 첫 주소가 된다.
     * 교차검증도 같은 칸을 읽는 rule 엔진과 비교하므로 이 차이는 잡지 못한다. 질의 문자열·끝 슬래시·공백·스킴 차이는 보지 않는다.
     */
    private static void noteOtherUrl(Draft draft, String slot, String candidate, String extracted) {
        String wanted = comparableUrl(LlmText.restore(candidate));
        if (extracted == null || wanted.isEmpty() || comparableUrl(extracted).equals(wanted)) {
            return;
        }
        draft.notes.add(URL_NOT_FIRST_NOTE, slot + " " + LlmText.oneLine(candidate) + " → " + extracted);
    }

    private static String comparableUrl(String url) {
        String bare = SourceText.stripSpaces(url);
        int query = bare.indexOf('?');
        bare = query < 0 ? bare : bare.substring(0, query);
        bare = bare.replaceFirst("(?i)^https?://", "").replaceFirst("[.,;)]+$", "");
        while (bare.endsWith("/")) {
            bare = bare.substring(0, bare.length() - 1);
        }
        return bare;
    }

    /**
     * 주소 후보 하나를 원문에서 찾아 그 칸의 원래 글을 돌려준다. 찾은 칸에서 그 자리의 함수로 주소를 꺼낼 수 없으면
     * 다음으로 고를 칸을 본다.
     *
     * @return 찾은 칸의 원래 글. 후보가 비었거나 버렸으면 null
     */
    private String locateUrl(Draft draft, SourceText source, Set<Integer> evidence, String slot, String value,
                             Predicate<String> extractable) {
        String candidate = LlmText.restore(value).strip();
        if (candidate.isEmpty()) {
            return null;
        }
        List<Hit> hits = source.urls(candidate, evidence);
        if (hits.isEmpty()) {
            draft.remove("V1", candidate, slot + " 후보를 원문에서 찾지 못함");
            draft.notes.add("원문에서 찾지 못한 주소 후보를 버림 (" + slot + "): " + candidate);
            return null;
        }
        for (Hit hit : hits) {
            if (extractable.test(hit.cell().text)) {
                return hit.cell().text;
            }
        }
        // N/A는 주소가 없다는 표기라 버린 것으로 알리지 않는다
        if (!NOT_AVAILABLE.equalsIgnoreCase(candidate)) {
            draft.remove("V1", candidate, slot + " 후보를 찾은 칸에서 주소를 꺼내지 못함");
            draft.notes.add("주소 후보를 찾은 칸에서 주소를 꺼내지 못해 버림 (" + slot + "): " + candidate);
        }
        return null;
    }

    /**
     * 영문 오퍼레이션명은 rule 엔진처럼 정보 표 이름 칸의 원래 글로만 본다. 목록 표에만 있는 이름은 rule 엔진도 쓰지 않으므로 버리되,
     * 문서에 있는 이름이라 검수할 거리가 아니어서 노트는 남기지 않는다. 표 어디에도 없는 이름은 지어낸 것이라 노트를 남긴다
     */
    private String locateOperationName(Draft draft, SourceText source, Set<Integer> evidence) {
        String name = LlmText.restore(draft.operation.operationName()).strip();
        if (name.isEmpty()) {
            return null;
        }
        if (source.operationNames(name, evidence).isEmpty()) {
            if (source.names(name, true, evidence).isEmpty()) {
                draft.remove("V1", name, "원문의 표에서 찾지 못함");
                draft.notes.add("원문의 표에서 찾지 못한 영문 오퍼레이션명을 버림: " + name);
            } else {
                draft.remove("V1", name, "정보 표의 이름 칸이 아닌 표(목록 표 등)에만 있음");
            }
            return null;
        }
        return name;
    }

    // ---- 주소 바로잡기, V2, V11 ----

    /** 겹치는 주소는 문서 전체를 봐야 바로잡을 수 있다 (rule 엔진의 {@code SpecBlockAssembler.finish}와 같은 함수) */
    private void correctSharedUrls(List<Draft> drafts) {
        List<Draft> eligible = drafts.stream().filter(draft -> !draft.dropped() && draft.url != null).toList();
        List<String> corrected = IrConventions.correctSharedUrls(
                eligible.stream().map(draft -> draft.url).toList(),
                eligible.stream().map(draft -> draft.candidates.exampleUrl()).toList(),
                eligible.stream().map(draft -> draft.correctionNotes).toList());
        for (int i = 0; i < eligible.size(); i++) {
            eligible.get(i).url = corrected.get(i);
        }
    }

    /** 고른 주소를 나누고 형식을 본다 (V2). 나눌 수 없거나 형식이 맞지 않으면 건너뛴다 */
    private void checkUrl(Draft draft) {
        if (draft.dropped()) {
            return;
        }
        if (draft.url == null) {
            draft.drop("오퍼레이션 주소(Call Back URL 등)를 찾지 못했습니다");
            return;
        }
        IrConventions.UrlParts parts = IrConventions.splitUrl(draft.url);
        if (!parts.ok()) {
            draft.drop(parts.error());
            return;
        }
        draft.parts = parts;
        String error = urlFormatError(parts);
        if (error != null) {
            draft.drop(error);
        }
    }

    /**
     * 생성 코드의 문자열 리터럴과 파일 경로에 들어가는 주소를 본다. 주소는 원문의 후보에서 골랐지만,
     * 원문 자체가 엉뚱한 글을 담을 수 있다(프롬프트 주입).
     */
    private static String urlFormatError(IrConventions.UrlParts parts) {
        String url = parts.baseUrl() + parts.endpoint();
        if (!url.regionMatches(true, 0, "http://", 0, 7) && !url.regionMatches(true, 0, "https://", 0, 8)) {
            return "주소가 http:// 또는 https://로 시작하지 않습니다: " + url;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (SourceText.isSpace(c) || Character.isISOControl(c) || "\"'“”‘’\\?#".indexOf(c) >= 0) {
                return "주소에 쓸 수 없는 글자(" + visible(c) + ")가 있습니다: " + url;
            }
        }
        if (!HOST_BASE_URL.matcher(parts.baseUrl()).matches()) {
            // 경로 없이 호스트만 적은 주소(http://apis.data.go.kr)는 호스트를 오퍼레이션으로 잘못 나눈다
            return "주소에 호스트 뒤의 경로가 없습니다: " + url;
        }
        if (parts.baseUrl().indexOf('{') >= 0 || parts.baseUrl().indexOf('}') >= 0) {
            return "경로 변수가 endpoint가 아니라 baseUrl에 있습니다: " + url;
        }
        if (!JavaNameRules.isValidApiId(parts.apiId())) {
            return "주소로 apiId를 만들 수 없습니다: " + url;
        }
        return null;
    }

    private static String visible(char c) {
        return SourceText.isSpace(c) || Character.isISOControl(c) ? String.format(Locale.ROOT, "U+%04X", (int) c) : String.valueOf(c);
    }

    /**
     * 바로잡은 뒤에도 같은 주소나 같은 apiId를 가진 오퍼레이션은 뒤의 것을 건너뛴다 (V11).
     * 서로 다른 주소도 apiId가 같을 수 있다({@code /v1/getList}와 {@code /v2/getList}). apiId는 생성 클래스명과 파일 경로다.
     */
    private void dropDuplicates(List<Draft> drafts) {
        List<Draft> kept = new ArrayList<>();
        for (Draft draft : drafts) {
            if (draft.dropped()) {
                continue;
            }
            String url = draft.parts.baseUrl() + draft.parts.endpoint();
            Draft sameUrl = kept.stream()
                    .filter(other -> IrConventions.sameUrl(other.parts.baseUrl() + other.parts.endpoint(), url))
                    .findFirst().orElse(null);
            Draft sameApiId = kept.stream()
                    .filter(other -> other.parts.apiId().equals(draft.parts.apiId()))
                    .findFirst().orElse(null);
            if (sameUrl != null) {
                draft.drop("주소가 앞 오퍼레이션(" + sameUrl.label() + ")과 같습니다: " + url);
                draft.remove("V11", url, "주소가 앞 오퍼레이션과 같음");
                sameUrl.notes.add(V11_URL_NOTE, draft.label());
            } else if (sameApiId != null) {
                draft.drop("apiId가 앞 오퍼레이션(" + sameApiId.label() + ")과 같습니다: " + draft.parts.apiId());
                draft.remove("V11", draft.parts.apiId(), "apiId가 앞 오퍼레이션과 같음");
                sameApiId.notes.add(V11_API_ID_NOTE, draft.label() + " (" + url + ")");
            } else {
                kept.add(draft);
            }
        }
    }

    // ---- V3, V4, V12: 요청 파라미터 ----

    private List<Parameter> checkParameters(Draft draft, SourceText source, Set<Integer> evidence) {
        List<Parameter> found = new ArrayList<>();
        for (LlmRequestParameter parameter : list(draft.operation.requestParameters())) {
            String raw = LlmText.restore(parameter.name()).strip();
            String name = cleanedName(source.names(raw, false, evidence));
            if (name == null) {
                draft.remove("V3", raw, "원문에 없음");
                draft.notes.add(V3_NOTE, raw);
            } else if (!isUsableName(name)) {
                draft.remove("V4", name, "따옴표·역슬래시·제어문자·마침표가 있거나 Java 이름 규칙에 맞지 않음");
                draft.notes.add(V4_NOTE, name);
            } else {
                found.add(new Parameter(name, parameter));
            }
        }
        return distinctParameters(draft, found);
    }

    /**
     * 겹치는 파라미터를 하나만 남긴다 (V12). 공통 구간 것과 오퍼레이션 것이 겹치면 오퍼레이션 것을, 같은 쪽끼리 겹치면
     * 앞의 것을 남긴다(rule 엔진의 {@code withCommon}·{@code distinctByName}). 이름은 달라도 Java 이름으로 바꾸면 겹치는
     * 파라미터({@code page_no}와 {@code pageNo})는 한 메서드의 인자가 되어 컴파일되지 않으므로 앞의 것을 남기고 노트를 남긴다.
     */
    private List<Parameter> distinctParameters(Draft draft, List<Parameter> parameters) {
        Set<String> own = parameters.stream().filter(parameter -> !parameter.source().common())
                .map(Parameter::name).collect(Collectors.toSet());
        List<Parameter> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Parameter parameter : parameters) {
            if (parameter.source().common() && own.contains(parameter.name())) {
                draft.remove("V12", parameter.name(), "오퍼레이션이 같은 이름을 다시 적은 공통 구간 파라미터");
            } else if (!seen.add(parameter.name())) {
                draft.remove("V12", parameter.name(), "앞 파라미터와 이름이 같음");
            } else {
                distinct.add(parameter);
            }
        }
        Set<String> collided = new HashSet<>();
        JavaNameRules.javaNameCollisions(distinct.stream().map(Parameter::name).toList()).values().forEach(names -> {
            String kept = names.get(0);
            for (String name : names.subList(1, names.size())) {
                collided.add(name);
                draft.remove("V12", name, "Java 이름이 앞 파라미터 " + kept + "와 겹침");
                draft.notes.add(V12_PARAMETER_NOTE, name + "(" + kept + "와 겹침)");
            }
        });
        return distinct.stream().filter(parameter -> !collided.contains(parameter.name())).toList();
    }

    // ---- V5, V6, V12, V7: 응답 필드 ----

    private List<Field> checkFields(Draft draft, SourceText source, Set<Integer> evidence) {
        ResponseStructure structure = draft.operation.responseStructure() == null
                ? ResponseStructure.STANDARD_ASSUMED
                : draft.operation.responseStructure();
        List<Field> found = new ArrayList<>();
        for (LlmResponseField field : list(draft.operation.responseFields())) {
            String raw = LlmText.restore(field.path()).strip();
            List<String> segments = Arrays.asList(raw.split("\\.", -1));
            // 빈 칸이나 가운데 낀 []는 원문에서 찾을 이름이 없으므로 모양(V6)을 먼저 본다
            if (!hasValidSegments(segments)) {
                draft.remove("V6", raw, "이름 규칙에 맞지 않거나 []가 칸 끝이 아님");
                draft.notes.add(V6_NOTE, raw);
                continue;
            }
            List<String> cleaned = new ArrayList<>();
            PathFailure failure = matchPath(segments, field.pathSource(), structure, source, evidence, cleaned);
            if (failure != null) {
                draft.remove("V5", raw, failure.note);
                draft.notes.add(failure.note, raw);
            } else if (!hasValidSegments(cleaned)) {
                // 찾은 자리의 원래 글로 다듬은 이름이 규칙에 맞지 않는 경우다
                draft.remove("V6", String.join(".", cleaned), "다듬은 이름이 이름 규칙에 맞지 않음");
                draft.notes.add(V6_NOTE, String.join(".", cleaned));
            } else {
                found.add(new Field(String.join(".", cleaned), field));
            }
        }
        if (structure == ResponseStructure.STANDARD_ASSUMED) {
            List<String> roots = source.responseExampleRoots(evidence);
            if (!roots.isEmpty() && !roots.contains("response")) {
                draft.notes.add("응답 구조를 표준 구조로 가정했지만 원문 응답 예제의 루트가 response가 아님: "
                        + String.join(", ", roots));
            }
        }
        return consistentTree(draft, distinctFields(draft, found));
    }

    /**
     * 응답 경로가 근거와 맞는지 본다 (V5). 마지막 칸은 {@code pathSource}가 가리키는 곳에서, 컨테이너 칸은
     * {@code responseStructure}가 가리키는 곳에서 찾는다. 표에만 있는 필드는 마지막 칸이 표에 있고 컨테이너 칸은 예제를 따른다.
     *
     * @param cleaned 찾은 자리의 원래 글로 다듬은 칸을 담을 목록
     * @return 맞지 않는 이유. 맞으면 null
     */
    private PathFailure matchPath(List<String> segments, LlmResponseField.PathSource pathSource,
                                  ResponseStructure structure, SourceText source, Set<Integer> evidence,
                                  List<String> cleaned) {
        int last = segments.size() - 1;
        if (structure == ResponseStructure.STANDARD_ASSUMED
                && !STANDARD_CONTAINERS.contains(segments.subList(0, last))) {
            return PathFailure.NOT_STANDARD;
        }
        for (int i = 0; i < last; i++) {
            String segment = segments.get(i);
            String name = bare(segment);
            if (structure == ResponseStructure.STANDARD_ASSUMED
                    || (structure == ResponseStructure.EXAMPLE && name.equals("item") && i > 0
                    && bare(segments.get(i - 1)).equals("items"))) {
                // 표준 골격은 원문에 없는 가정이고, JSON의 items 배열을 표준 경로로 옮긴 items.item[]에는 item 키가 없다
                cleaned.add(segment);
                continue;
            }
            String found = structure == ResponseStructure.EXAMPLE
                    ? cleanedName(source.keys(name, evidence))
                    : cleanedName(source.names(name, true, evidence));
            if (found == null) {
                return structure == ResponseStructure.EXAMPLE
                        ? PathFailure.CONTAINER_NOT_IN_EXAMPLE
                        : PathFailure.CONTAINER_NOT_IN_TABLE;
            }
            cleaned.add(found + suffix(segment));
        }
        String leaf = bare(segments.get(last));
        String found;
        PathFailure failure;
        if (pathSource == LlmResponseField.PathSource.FIELD_TABLE) {
            found = cleanedName(source.names(leaf, true, evidence));
            failure = PathFailure.LEAF_NOT_IN_TABLE;
        } else if (pathSource == LlmResponseField.PathSource.RESPONSE_EXAMPLE) {
            found = cleanedName(source.keys(leaf, evidence));
            failure = PathFailure.LEAF_NOT_IN_EXAMPLE;
        } else {
            // 출처를 적지 않은 응답은 두 곳을 다 본다. 스키마는 required지만 회사 응답을 믿지 않는다
            found = cleanedName(source.keys(leaf, evidence));
            found = found != null ? found : cleanedName(source.names(leaf, true, evidence));
            failure = PathFailure.LEAF_NOT_IN_TABLE;
        }
        if (found == null) {
            return failure;
        }
        cleaned.add(found + suffix(segments.get(last)));
        return null;
    }

    /**
     * 겹치는 응답 필드를 하나만 남긴다 (V12). 같은 경로는 공통 구간 것보다 오퍼레이션 것을, 같은 쪽끼리는 앞의 것을 남긴다.
     * 같은 부모 아래에서 Java 이름이 같아지는 칸({@code station_name}과 {@code stationName})이나 getter·setter 이름이 같아지는
     * 칸({@code items}와 {@code _items}의 {@code getItems})은 한 DTO 클래스에 들어가 컴파일되지 않으므로 앞의 것을 남기고 노트를 남긴다.
     * generator의 {@code CodeGenerator}와 같은 규칙이다.
     */
    private List<Field> distinctFields(Draft draft, List<Field> fields) {
        Set<String> own = fields.stream().filter(field -> !field.source().common())
                .map(Field::path).collect(Collectors.toSet());
        List<Field> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Field field : fields) {
            if (field.source().common() && own.contains(field.path())) {
                draft.remove("V12", field.path(), "오퍼레이션이 같은 경로를 다시 적은 공통 구간 필드");
            } else if (!seen.add(field.path())) {
                draft.remove("V12", field.path(), "앞 필드와 경로가 같음");
            } else {
                distinct.add(field);
            }
        }
        // 부모 경로 → (Java 이름·getter 이름 키 → 칸 이름)
        Map<String, Map<String, String>> children = new HashMap<>();
        List<Field> kept = new ArrayList<>();
        for (Field field : distinct) {
            String clash = javaNameClash(field.path(), children);
            if (clash != null) {
                draft.remove("V12", field.path(), "Java 이름이 같은 자리의 앞 칸 " + clash + "와 겹침");
                draft.notes.add(V12_FIELD_NOTE, field.path() + "(" + clash + "와 겹침)");
                continue;
            }
            String parent = "";
            for (String segment : field.path().split("\\.")) {
                String name = bare(segment);
                Map<String, String> siblings = children.computeIfAbsent(parent, key -> new HashMap<>());
                for (String key : javaKeys(name)) {
                    siblings.putIfAbsent(key, name);
                }
                parent = parent + "/" + name;
            }
            kept.add(field);
        }
        return kept;
    }

    /** @return 같은 부모 아래에서 Java 이름이 겹치는 앞 칸의 이름. 없으면 null */
    private static String javaNameClash(String path, Map<String, Map<String, String>> children) {
        String parent = "";
        for (String segment : path.split("\\.")) {
            String name = bare(segment);
            Map<String, String> siblings = children.getOrDefault(parent, Map.of());
            for (String key : javaKeys(name)) {
                String existing = siblings.get(key);
                if (existing != null && !existing.equals(name)) {
                    return existing;
                }
            }
            parent = parent + "/" + name;
        }
        return null;
    }

    /**
     * 생성 DTO에서 같은 필드가 되는지 보는 키와 같은 getter·setter가 되는지 보는 키. 둘 중 하나라도 같으면 겹친다
     * ({@link JavaNameRules#accessorNameCollisions})
     */
    private static List<String> javaKeys(String name) {
        return List.of("field:" + JavaNameRules.canonicalIdentifier(JavaNameRules.javaName(name)),
                "accessor:" + JavaNameRules.accessorKey(name));
    }

    /**
     * 경로 트리가 일관되지 않은 필드를 버린다 (V7). generator의 {@code DtoTreeBuilder}와 같은 규칙이다.
     * 같은 노드가 값 필드이면서 컨테이너일 수 없고, 같은 노드의 {@code []} 표기가 같아야 한다. 앞의 것을 남긴다.
     * 루트가 여럿인 것은 충돌이 아니다. generator가 받지 못해 정규화가 지원 범위 밖으로 건너뛴다.
     */
    private List<Field> consistentTree(Draft draft, List<Field> fields) {
        Map<String, Node> roots = new LinkedHashMap<>();
        List<Field> kept = new ArrayList<>();
        for (Field field : fields) {
            String[] segments = field.path().split("\\.");
            String conflict = treeConflict(roots, segments);
            if (conflict != null) {
                draft.remove("V7", field.path(), conflict);
                draft.notes.add(conflict, field.path());
                continue;
            }
            Map<String, Node> level = roots;
            Node node = null;
            for (String segment : segments) {
                node = level.computeIfAbsent(bare(segment), name -> new Node(segment.endsWith(LIST_SUFFIX)));
                level = node.children;
            }
            node.leaf = true;
            kept.add(field);
        }
        return kept;
    }

    private static String treeConflict(Map<String, Node> roots, String[] segments) {
        Map<String, Node> level = roots;
        for (int i = 0; i < segments.length; i++) {
            Node node = level.get(bare(segments[i]));
            if (node == null) {
                return null;
            }
            if (node.list != segments[i].endsWith(LIST_SUFFIX)) {
                return V7_LIST_NOTE;
            }
            boolean last = i == segments.length - 1;
            if (last ? !node.children.isEmpty() : node.leaf) {
                return V7_LEAF_NOTE;
            }
            level = node.children;
        }
        return null;
    }

    /** 경로 트리의 노드 */
    private static final class Node {
        private final boolean list;
        private final Map<String, Node> children = new LinkedHashMap<>();
        private boolean leaf;

        private Node(boolean list) {
            this.list = list;
        }
    }

    // ---- V8, V9: 결과코드 필드, 성공코드 후보, 에러코드 ----

    /**
     * 결과코드 필드 이름이 원문에 있는지 본다 (V8). 생성 클라이언트는 이 이름을 인터셉터에 넘겨 기본 이름보다 먼저 찾고, 정규화는 같은 이름의
     * 응답 필드를 문자열 결과코드로 둔다. 그래서 지어낸 이름이나 결과메시지 필드({@code resultMsg})를 받으면 정상 응답이 모두 예외가 될 수 있다.
     * 검증을 통과한 응답 필드의 이름이거나 원문의 표 칸·예제 키에 있어야 하고, 결과메시지 필드가 아니어야 한다.
     *
     * @return 통과한 이름(LLM이 적은 그대로, 경로일 수 있다). 없거나 버렸으면 빈 문자열
     */
    private String checkResultCodeField(Draft draft, SourceText source, Set<Integer> evidence) {
        LlmResultCode resultCode = draft.operation.resultCode();
        String fieldName = resultCode == null ? "" : LlmText.oneLine(resultCode.fieldName());
        if (fieldName.isEmpty()) {
            return "";
        }
        String leaf = leafName(fieldName);
        if (isMessageField(leaf)) {
            draft.remove("V8", fieldName, "결과메시지 필드");
            draft.notes.add(RESULT_MESSAGE_FIELD_NOTE, fieldName);
            return "";
        }
        boolean inFields = draft.fields.stream().anyMatch(field -> field.leaf().equals(leaf));
        if (!leaf.isEmpty() && (inFields || !source.keys(leaf, evidence).isEmpty()
                || !source.names(leaf, true, evidence).isEmpty())) {
            return fieldName;
        }
        draft.remove("V8", fieldName, "결과코드 필드 이름을 원문에서 찾지 못함");
        draft.notes.add(RESULT_FIELD_NOT_FOUND_NOTE, fieldName);
        return "";
    }

    /** 성공코드 후보가 출처가 가리키는 자리에 있고 {@code [A-Za-z0-9_-]+} 꼴인지 본다 (V8). 버린 후보는 노트를 남기지 않는다 */
    private List<SuccessCode> checkSuccessCodes(Draft draft, SourceText source, Set<Integer> evidence) {
        LlmResultCode resultCode = draft.operation.resultCode();
        if (resultCode == null) {
            return List.of();
        }
        String fieldName = leafName(draft.resultCodeField);
        Predicate<String> resultCodeName = name -> !name.isEmpty()
                && (name.equals(fieldName) || IrConventions.isResultCodeField(name));
        List<SuccessCode> found = new ArrayList<>();
        for (LlmSuccessCode candidate : list(resultCode.successCodes())) {
            String code = LlmText.restore(candidate.code()).strip();
            if (!CODE.matcher(code).matches()) {
                draft.remove("V8", code, "성공코드 꼴([A-Za-z0-9_-]+)이 아님");
                continue;
            }
            List<Hit> hits = candidate.source() == null ? List.of() : switch (candidate.source()) {
                case FIELD_TABLE -> source.codesInResultCodeRows(code, resultCodeName, evidence);
                case RESPONSE_EXAMPLE -> source.resultCodeValues(code, resultCodeName, evidence);
                case ERROR_TABLE -> source.codesInNormalRows(code, evidence);
            };
            if (hits.isEmpty()) {
                draft.remove("V8", code, "출처(" + candidate.source() + ")가 가리키는 자리에 없음");
                continue;
            }
            found.add(new SuccessCode(code, candidate.source(), hits.get(0).cell().order));
        }
        return found;
    }

    /**
     * 에러코드가 원문에 있는지 본다 (V9). 버린 항목은 노트를 남기지 않는다.
     *
     * @param draft 오퍼레이션의 에러코드면 버린 항목을 남길 오퍼레이션. 문서 공통 에러코드면 null
     */
    private List<LlmErrorCode> checkErrorCodes(List<LlmErrorCode> errors, SourceText source, Set<Integer> evidence,
                                               Draft draft) {
        List<LlmErrorCode> found = new ArrayList<>();
        for (LlmErrorCode error : list(errors)) {
            if (error == null) {
                continue;
            }
            String code = LlmText.restore(error.code()).strip();
            if (source.names(code, false, evidence).isEmpty()) {
                if (draft != null) {
                    draft.remove("V9", code, "원문에 없음");
                }
            } else {
                found.add(new LlmErrorCode(code, LlmText.restore(error.message())));
            }
        }
        return found;
    }

    // ---- 이름 ----

    /**
     * 찾은 자리의 원래 글을 rule 엔진과 같은 이름 정리로 다듬는다. 칸 폭 때문에 꺾인 이름({@code pm} 줄바꿈 {@code 25Value})이
     * 이어진다. 다듬은 이름이 비면 다음으로 고를 자리를 본다.
     *
     * @return 다듬은 이름. 찾지 못했으면 null
     */
    private static String cleanedName(List<Hit> hits) {
        for (Hit hit : hits) {
            String name = IrConventions.cleanName(hit.span());
            if (!name.isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /**
     * 요청 파라미터 이름과 응답 경로의 칸 이름이 생성 코드에 들어갈 수 있는지 본다 (V4·V6).
     * 원래 이름은 따옴표·역슬래시·제어문자·마침표가 없어야 하고, generator가 만들 Java 이름은 식별자로 컴파일되어야 한다.
     * 원래 이름이 식별자 꼴인지로 거르지 않는다. 공백·한글·{@code -}가 든 이름은 지금도 잘 생성된다.
     */
    private static boolean isUsableName(String name) {
        return JavaNameRules.isSafeRawName(name)
                && name.indexOf('.') < 0
                && name.chars().noneMatch(c -> OTHER_QUOTES.indexOf(c) >= 0)
                && JavaNameRules.hasValidJavaName(name);
    }

    /**
     * 경로의 각 칸이 이름 규칙을 지키고 {@code []}가 칸 끝에만 오는지 본다 (V6).
     * 칸은 DTO 필드가 되므로 getter가 Object의 final 메서드 {@code getClass()}가 되는 이름({@code _class})도 거른다.
     */
    private static boolean hasValidSegments(List<String> segments) {
        for (String segment : segments) {
            String name = bare(segment);
            if (name.isEmpty() || name.indexOf('[') >= 0 || name.indexOf(']') >= 0 || !isUsableName(name)
                    || JavaNameRules.accessorKey(name).equals("Class")) {
                return false;
            }
        }
        return true;
    }

    /** 칸 이름에서 반복 표기를 뗀다 ({@code item[]} → {@code item}) */
    private static String bare(String segment) {
        return segment.endsWith(LIST_SUFFIX) ? segment.substring(0, segment.length() - LIST_SUFFIX.length()) : segment;
    }

    private static String suffix(String segment) {
        return segment.endsWith(LIST_SUFFIX) ? LIST_SUFFIX : "";
    }

    /**
     * 결과메시지 필드인지 본다. rule 엔진의 판정(resultMsg·resultMessage)에 더해, 인터셉터가 메시지로 읽는 {@code returnAuthMsg}·{@code errorMsg}·
     * {@code ERROR_MSG}처럼 이름이 Msg·Message로 끝나는 필드도 결과코드가 아니다
     */
    private static boolean isMessageField(String name) {
        if (IrConventions.isResultCodeField(name)) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT).replace("_", "");
        return IrConventions.isResultCodeOrMessageField(name) || key.endsWith("msg") || key.endsWith("message");
    }

    /** 결과코드 필드 이름이 경로로 오면({@code response.header.resultCode}) 마지막 칸만 본다 */
    private static String leafName(String fieldName) {
        return bare(fieldName.substring(fieldName.lastIndexOf('.') + 1));
    }

    /** 중간 스키마는 null을 쓰지 않지만 회사 응답을 믿지 않는다 */
    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    /** 검증 중인 오퍼레이션 하나 */
    private static final class Draft {
        private final int position;
        private final LlmOperation operation;
        private final String title;
        private final GroupedNotes notes = new GroupedNotes(NotePrefix.VALIDATION);
        private final List<Removal> removals = new ArrayList<>();
        private final List<String> selectionNotes = new ArrayList<>();
        private final List<String> correctionNotes = new ArrayList<>();
        private String operationName;
        private UrlSources sources;
        private UrlCandidates candidates;
        private String url;
        private IrConventions.UrlParts parts;
        private List<Parameter> parameters = List.of();
        private List<Field> fields = List.of();
        private String resultCodeField = "";
        private List<SuccessCode> successCodes = List.of();
        private List<LlmErrorCode> errorCodes = List.of();
        private String dropReason;

        private Draft(int position, LlmOperation operation) {
            this.position = position;
            this.operation = operation;
            this.title = LlmText.oneLine(operation.title());
        }

        private void remove(String rule, String item, String reason) {
            removals.add(new Removal(rule, item, reason));
        }

        /** 처음 건너뛴 이유를 남긴다 */
        private void drop(String reason) {
            if (dropReason == null) {
                dropReason = reason;
            }
        }

        private boolean dropped() {
            return dropReason != null;
        }

        private String label() {
            return ValidatedOperation.label(operationName, title, position);
        }

        private ValidatedOperation result() {
            Address address = new Address(sources, candidates, url, parts, selectionNotes, correctionNotes);
            return new ValidatedOperation(position, operation, operationName, title, address, parameters, fields,
                    resultCodeField, successCodes, errorCodes, notes.lines(), removals, dropReason);
        }
    }
}
