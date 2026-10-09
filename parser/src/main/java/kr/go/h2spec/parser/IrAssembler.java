package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 상세기능정보/요청/응답 표를 IR JSON(ObjectNode)으로 조립한다. */
public class IrAssembler {

    private static final String PARSER_VERSION = "0.1.0";
    private static final String OPERATION_PREFIX = "get";
    private static final Pattern INTEGER_SAMPLE = Pattern.compile("^\\d+$");
    private static final Pattern NUMBER_SAMPLE = Pattern.compile("^\\d+\\.\\d+$");
    /** 선행 0이 붙은 정수 샘플(0311 등)은 숫자로 만들면 값이 바뀐다 */
    private static final Pattern LEADING_ZERO_SAMPLE = Pattern.compile("^0\\d+$");
    /**
     * 항목구분 칸의 카디널리티 표기(0..n, 1..1). 컨테이너 행 판정의 필요조건이지만
     * 충분조건은 아니다 — 반복 데이터 필드마다 이 표기를 붙이는 기관이 있다.
     */
    private static final Pattern CONTAINER_CARDINALITY = Pattern.compile("^\\d+\\.\\.(\\d+|n|N)$");
    /** 값이 없음을 나타내는 표기. 컨테이너 행은 항목크기·샘플데이터를 이렇게 비운다 */
    private static final String EMPTY_CELL = "-";
    /** 열 위치만으로 읽는 표준 표의 최소 열 수 (항목명(영문)~항목설명) */
    private static final int STANDARD_COLUMNS = 6;
    /** 머리행을 알아보는 표식 */
    private static final String NAME_LABEL_MARKER = "항목명";
    /** 하위 필드를 감싸기만 하는 표준 응답 골격 이름 (소문자) */
    private static final Set<String> WRAPPER_NAMES = Set.of("response", "header", "body", "items", "item");
    /** 샘플 칸의 구조 도식: "키": [ 또는 "키": { 로 시작한다 */
    private static final Pattern STRUCTURE_SAMPLE = Pattern.compile("^[\"“']?[A-Za-z_][\\w]*[\"”']?\\s*:\\s*[\\[{]");
    /** 항목크기 칸에 크기 대신 적은 자료형 */
    private static final Pattern SIZE_TYPE_WORD = Pattern.compile("(?i)(number|numeric|string|varchar|char|text|array|object|list|int|integer|long)(\\(\\d+\\))?");
    /** 하위 목록을 감싸는 컨테이너 이름꼴 */
    private static final Pattern LIST_NAME = Pattern.compile("(?i)(list|array|items)$");
    /** 타입 열이 컨테이너를 뜻하는 값 (소문자, 공백 제거) */
    private static final Set<String> CONTAINER_TYPES = Set.of("array", "object", "list", "jsonarray", "jsonobject",
            "배열", "객체");
    /** 필수 항목을 뜻하는 항목구분·필수여부 값 (소문자, 공백 제거) */
    private static final Set<String> REQUIRED_MARKS = Set.of("필수", "필", "y", "yes", "o", "○", "●", "◯", "◎",
            "required", "mandatory", "m", "true");
    private static final Pattern INTEGER_TYPE = Pattern.compile("^(int|integer|long|short|bigint|smallint|정수)");
    private static final Pattern DECIMAL_TYPE = Pattern.compile("^(double|float|decimal|numeric|real|실수)");
    private static final Pattern STRING_TYPE = Pattern.compile("^(string|str|varchar|varchar2|char|text|문자|date|datetime|timestamp)");
    private static final Pattern BOOLEAN_TYPE = Pattern.compile("^(boolean|bool)$");
    /**
     * 산술 대상이 아닌 식별자·코드·일자를 가리키는 이름 접미사 (소문자 비교).
     * "tm"은 거리(tm)·item처럼 시각이 아닌 이름까지 걸리므로 넣지 않고 설명 키워드에 맡긴다.
     */
    private static final List<String> NON_NUMERIC_NAME_SUFFIXES =
            List.of("cd", "code", "no", "id", "dt", "ym", "ymd");
    /** 산술 대상이 아님을 드러내는 항목설명 키워드 */
    private static final List<String> NON_NUMERIC_DESCRIPTION_KEYWORDS =
            List.of("코드", "번호", "일자", "년월일", "시각", "일시");
    /**
     * 공공데이터 표준 응답에서 response.header 바로 아래에 오는 필드.
     * RESULT_CODE처럼 대문자·밑줄로 적는 기관이 있어 {@link #metaKey}로 맞춘 꼴로 비교한다.
     */
    private static final Set<String> HEADER_FIELDS = Set.of("resultcode", "resultmsg", "resultmessage");
    private static final String RESULT_CODE_KEY = "resultcode";
    /** 공공데이터 표준 응답에서 response.body 바로 아래에 오는 페이징 메타 필드 */
    private static final Set<String> BODY_META_FIELDS = Set.of("numofrows", "pageno", "totalcount");
    /**
     * 요청에 대응 파라미터가 없어도 body 메타로 보는 응답 전용 필드.
     * 전체 건수는 서버가 계산해 내려주므로 요청 파라미터에 나타나지 않는다.
     */
    private static final Set<String> RESPONSE_ONLY_META_FIELDS = Set.of("totalcount");
    /** 페이징 메타로 볼 만한 이름꼴. 이름만으로는 부족해 요청 파라미터 존재 여부와 함께 본다 */
    private static final Pattern PAGING_NAME = Pattern.compile("(cnt|count|rows)$|^page(No|Index)$|^currentPage$",
            Pattern.CASE_INSENSITIVE);
    /**
     * 응답 포맷을 고르는 요청 파라미터. 이름이 기관마다 다르다.
     * generator의 ClientEmitter가 포맷 값을 채워 보내는 이름과 같아야 한다. 여기서만 넓히면
     * 클라이언트는 포맷 파라미터를 보내지 않는데 DTO는 JSON을 기대해 응답을 읽지 못한다.
     */
    private static final Set<String> RESPONSE_FORMAT_PARAMS = Set.of("returnType", "dataType", "resultType");
    private static final String XML_FORMAT = "XML";
    private static final String JSON_FORMAT = "JSON";
    private static final String SUCCESS_RESULT_CODE = "00";
    /** 숫자로 시작하는 오퍼레이션 경로로 만든 apiId 앞에 붙이는 접두사 */
    private static final String API_ID_PREFIX = "Api";
    /** 엔드포인트의 경로 변수 */
    private static final Pattern PATH_TEMPLATE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");
    /** Call Back URL 칸을 비워두는 대신 적어두는 값 */
    private static final String NOT_AVAILABLE = "N/A";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedApi assemble(String sourceFile, String sourceFormat, Map<String, String> info,
                              List<List<String>> requestRows, List<List<String>> responseRows) {
        String url = callBackUrl(info);
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash < 0 || lastSlash == url.length() - 1) {
            throw new IllegalArgumentException(
                    "Call Back URL에서 오퍼레이션 경로를 찾을 수 없습니다: " + url);
        }
        // "…/proxy/<id>/{your_appkey}"처럼 주소 끝에 경로 변수가 오면 그 앞 칸이 오퍼레이션이다
        int operationSlash = lastSlash;
        while (isTemplateSegment(url.substring(operationSlash + 1).split("/")[0]) && operationSlash > 0) {
            int previous = url.lastIndexOf('/', operationSlash - 1);
            if (previous < 0 || url.substring(0, previous).endsWith("/")) {
                break;
            }
            operationSlash = previous;
        }
        String segment = url.substring(operationSlash + 1);
        String baseUrl = url.substring(0, operationSlash);
        String apiId = toApiId(segment.split("/")[0]);

        List<String> reviewNotes = new ArrayList<>();
        String assemblyNote = info.get(SpecBlockAssembler.REVIEW_NOTE_KEY);
        if (assemblyNote != null) {
            reviewNotes.addAll(List.of(assemblyNote.split("\n")));
        }
        List<List<String>> requests = dataRows(requestRows);
        List<List<String>> responses = dataRows(responseRows);
        Set<String> requestParamNames = namesOf(requests);

        ObjectNode api = objectMapper.createObjectNode();
        api.put("apiId", apiId);
        api.put("apiName", oneLine(infoValue(info, SpecLabels.operationTitleKeys(), apiId)));
        api.put("provider", "");
        api.put("baseUrl", baseUrl);
        api.put("endpoint", "/" + segment);
        api.put("httpMethod", "GET");
        api.put("description", oneLine(infoValue(info, SpecLabels.operationDescriptionKeys(), "")));
        api.put("authType", "SERVICE_KEY_QUERY_PARAM");
        api.put("responseFormat", responseFormat(requests));
        ArrayNode parameters = requestParameters(requests, requestParamNames, reviewNotes);
        addPathParameters(parameters, segment);
        api.set("requestParameters", parameters);
        api.set("responseFields", responseFields(responses, requestParamNames, reviewNotes));
        api.set("errorSpec", errorSpec());

        ObjectNode ir = objectMapper.createObjectNode();
        ir.set("metadata", metadata(sourceFile, sourceFormat, reviewNotes));
        ir.set("api", api);
        ir.set("generatorHints", generatorHints(apiId));
        return new ParsedApi(apiId, ir);
    }

    /**
     * Call Back URL을 우선 쓰고, 없거나 N/A인 문서는 서비스 개요 표의 주소와
     * 오퍼레이션명(영문)으로 조립한다. 천문연구원 특일정보처럼 Call Back URL 칸을
     * N/A로 비워두고 주소를 별도 표에 적는 문서가 있다.
     */
    private String callBackUrl(Map<String, String> info) {
        String resolved = info.get(SpecBlockAssembler.ENDPOINT_URL_KEY);
        if (resolved != null && !resolved.isBlank()) {
            return resolved.replaceAll("/+$", "");
        }
        String callBack = info.entrySet().stream()
                .filter(entry -> entry.getKey().contains("Call Back"))
                .map(entry -> entry.getValue().replaceAll("\\s+", ""))
                .filter(value -> !value.isBlank() && !NOT_AVAILABLE.equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
        if (callBack != null) {
            return callBack;
        }
        String serviceUrl = info.get(SpecBlockAssembler.SERVICE_URL_KEY);
        String operation = info.entrySet().stream()
                .filter(entry -> entry.getKey().contains("오퍼레이션명(영문)"))
                .map(entry -> entry.getValue().trim())
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElse(null);
        if (serviceUrl != null && operation != null) {
            return serviceUrl.replaceAll("/+$", "") + "/" + operation;
        }
        throw new IllegalArgumentException("상세기능정보 표에 Call Back URL이 없습니다");
    }

    /**
     * 정보 표에서 키 후보 순서대로 첫 값을 찾는다. 키는 공백을 뺀 꼴로 비교한다
     * ("상세기능 설명"과 "상세기능설명", "오퍼레이션 설명"을 같은 자리로 본다).
     * 영문 식별자만 담긴 값(getXxx)은 국문명 자리에 맞지 않아 건너뛴다.
     */
    private String infoValue(Map<String, String> info, List<String> keys, String fallback) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                String value = entry.getValue().strip();
                if (SpecLabels.normalize(entry.getKey()).equals(key) && !value.isEmpty()
                        && !NOT_AVAILABLE.equalsIgnoreCase(value) && !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    return value;
                }
            }
        }
        return fallback;
    }

    /**
     * getMsrstnList → MsrstnList (get 접두사 제거, 첫 글자 대문자).
     * 떼고 나면 숫자로 시작하는 이름(get24DivisionsInfo)은 자바 클래스명으로 쓸 수 없어
     * 접두사를 남긴다 → Get24DivisionsInfo.
     */
    private String toApiId(String segment) {
        String name = segment.startsWith(OPERATION_PREFIX)
                ? segment.substring(OPERATION_PREFIX.length())
                : segment;
        if (name.isEmpty() || Character.isDigit(name.charAt(0))) {
            name = segment;
        }
        // 클래스명으로 쓰이므로 식별자에 못 쓰는 글자(".do", "-")는 밑줄로 바꾸고,
        // 숫자로 시작하는 경로("2058570a…")는 접두사를 붙인다
        name = name.replaceAll("[^A-Za-z0-9_]", "_");
        if (!Character.isLetter(name.charAt(0))) {
            name = API_ID_PREFIX + name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private boolean isTemplateSegment(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    /**
     * 엔드포인트의 경로 변수({your_appkey})가 요청 명세 표에 없으면 필수 path 파라미터로 채운다.
     * 표에 같은 이름의 query 파라미터로 적혀 있으면 path로 바꾼다.
     */
    private void addPathParameters(ArrayNode parameters, String endpoint) {
        Matcher template = PATH_TEMPLATE.matcher(endpoint);
        int insertAt = 0;
        while (template.find()) {
            String name = template.group(1);
            ObjectNode existing = null;
            for (JsonNode param : parameters) {
                if (name.equals(param.get("name").asText())) {
                    existing = (ObjectNode) param;
                }
            }
            if (existing != null) {
                existing.put("in", "path");
                existing.put("required", true);
                continue;
            }
            ObjectNode param = objectMapper.createObjectNode();
            param.put("name", name);
            param.put("in", "path");
            param.put("type", "string");
            param.put("required", true);
            param.put("description", "주소의 {" + name + "} 자리에 넣는 값");
            parameters.insert(insertAt++, param);
        }
    }

    private ArrayNode requestParameters(List<List<String>> rows, Set<String> requestParamNames,
                                        List<String> reviewNotes) {
        ArrayNode parameters = objectMapper.createArrayNode();
        for (List<String> cells : rows) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String gubun = cells.get(3);
            String sample = cells.get(4);
            String description = describe(cells.get(5), korName);

            ObjectNode param = parameters.addObject();
            boolean settled = isBodyMeta(name, requestParamNames);
            param.put("name", name);
            param.put("in", "query");
            param.put("type", inferType(sample, name, description, declaredType(cells), settled ? null : reviewNotes));
            param.put("required", isRequired(gubun));
            param.put("description", description);
            if (!sample.isBlank() && !"-".equals(sample)) {
                param.put("example", oneLine(sample));
            }
        }
        return parameters;
    }

    private Set<String> namesOf(List<List<String>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> cells : rows) {
            names.add(cells.get(0));
        }
        return names;
    }

    private ArrayNode responseFields(List<List<String>> rows, Set<String> requestParamNames,
                                     List<String> reviewNotes) {
        ArrayNode fields = objectMapper.createArrayNode();
        for (List<String> cells : rows) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String sample = cells.get(4);
            String description = describe(cells.get(5), korName);

            ObjectNode field = fields.addObject();
            field.put("path", pathFor(name, requestParamNames));
            // 결과코드/메시지는 샘플이 숫자("00")여도 선행 0 보존을 위해 항상 문자열
            boolean settled = isBodyMeta(name, requestParamNames);
            field.put("type", HEADER_FIELDS.contains(metaKey(name))
                    ? "string"
                    : inferType(sample, name, description, declaredType(cells), settled ? null : reviewNotes));
            field.put("description", description);
            if (RESULT_CODE_KEY.equals(metaKey(name))) {
                field.put("isResultIndicator", true);
            }
        }
        return fields;
    }

    /**
     * 응답 포맷은 포맷 파라미터의 샘플데이터(문서가 적어둔 기본값)로 정한다.
     * 항목설명 문구는 "xml 또는json", "결과형식(xml/json)"처럼 기관마다 달라 근거로 삼기 어렵다.
     */
    private String responseFormat(List<List<String>> requestRows) {
        for (List<String> cells : requestRows) {
            if (RESPONSE_FORMAT_PARAMS.contains(cells.get(0)) && JSON_FORMAT.equalsIgnoreCase(cells.get(4).strip())) {
                return JSON_FORMAT;
            }
        }
        return XML_FORMAT;
    }

    /**
     * 항목설명이 비어 있으면 국문 항목명을 설명으로 쓴다. 셀 안의 여러 줄은 한 줄로 잇는다.
     * 생성 코드의 Javadoc 한 줄 주석에 그대로 들어가기 때문이다.
     */
    private String describe(String description, String korName) {
        return oneLine(description.isBlank() ? korName : description);
    }

    private String oneLine(String text) {
        return text.replaceAll("\\s*\\n\\s*", " ").strip();
    }

    /** 표에는 계층 정보가 없어 공공데이터 표준 응답 구조(header/body/items)를 가정한다. */
    private String pathFor(String name, Set<String> requestParamNames) {
        if (HEADER_FIELDS.contains(metaKey(name))) {
            return "response.header." + name;
        }
        if (isBodyMeta(name, requestParamNames)) {
            return "response.body." + name;
        }
        return "response.body.items.item[]." + name;
    }

    /**
     * 페이징 메타 필드는 기관마다 이름이 다르다(numOfRows, recordCnt 등). 이름꼴만 보면
     * "황사 발생 회차"(tmCnt)처럼 진짜 데이터까지 걸리므로, 같은 이름이 요청 파라미터에도
     * 있을 때만 메타로 본다. 조회 조건으로 넘긴 값을 응답이 되돌려주는 것이 페이징 필드다.
     */
    private boolean isBodyMeta(String name, Set<String> requestParamNames) {
        String key = metaKey(name);
        if (BODY_META_FIELDS.contains(key) || RESPONSE_ONLY_META_FIELDS.contains(key)) {
            return true;
        }
        return PAGING_NAME.matcher(name).find() && requestParamNames.contains(name);
    }

    /**
     * 헤더 행, 셀 수가 모자란 행(각주 등), items처럼 하위 표를 감싸기만 하는
     * 컨테이너 행을 걸러낸 실제 데이터 행만 표준 열 순서({@link FieldColumns})로 반환한다.
     * 머리행이 있으면 그 라벨로 열 위치를 정하고, 없으면 이미 표준 열 순서로 맞춰진 행으로 본다.
     */
    private List<List<String>> dataRows(List<List<String>> rows) {
        FieldColumns columns = null;
        List<List<String>> result = new ArrayList<>();
        for (List<String> raw : rows) {
            FieldColumns header = FieldColumns.detect(raw);
            if (header != null) {
                columns = header;
                continue;
            }
            List<String> row;
            if (columns != null) {
                row = columns.canonical(raw);
                if (row == null) {
                    continue;
                }
            } else {
                if (raw.size() < STANDARD_COLUMNS) {
                    continue;
                }
                row = new ArrayList<>(raw);
                while (row.size() < FieldColumns.COUNT) {
                    row.add("");
                }
            }
            if (row.get(0).isBlank() || row.get(0).contains(NAME_LABEL_MARKER)) {
                continue;
            }
            if (!isContainerRow(row)) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 하위 표를 감싸기만 하는 컨테이너 행인지 본다. 항목구분이 카디널리티 표기이면서
     * 항목크기와 샘플데이터가 모두 비어 있어야 한다. 카디널리티만으로 판정하면
     * 반복 데이터 필드마다 0..n을 붙이는 문서에서 실제 필드까지 사라진다.
     */
    private boolean isContainerRow(List<String> cells) {
        String name = cells.get(0);
        String size = cells.get(2).strip();
        boolean noSample = isEmptyCell(cells.get(4));
        boolean wrapper = WRAPPER_NAMES.contains(name.toLowerCase(Locale.ROOT));
        if (CONTAINER_CARDINALITY.matcher(cells.get(3).strip()).matches() && noSample
                && (isEmptyCell(size) || SIZE_TYPE_WORD.matcher(size).matches() || wrapper || isEmptyCell(cells.get(1)))) {
            // 항목크기 칸에 "number" 같은 자료형을 적거나 컨테이너에도 크기를 적는 문서가 있어
            // 크기가 있으면 이름(items)이나 빈 국문명으로 컨테이너임을 확인한다. "10,7"·"가변" 같은 크기는 값 필드다
            return true;
        }
        if (CONTAINER_TYPES.contains(SpecLabels.normalize(cells.get(6))) && noSample) {
            return true;
        }
        if (STRUCTURE_SAMPLE.matcher(cells.get(4).strip()).find()) {
            // 샘플 칸에 값 대신 하위 구조 도식("“resultList”:[{“meta”: {...}") 을 적은 행
            return true;
        }
        if (LIST_NAME.matcher(name).find() && isEmptyCell(size) && isEmptyCell(cells.get(3)) && noSample) {
            // "zone_Spot_List | - | - | -"처럼 값 칸이 모두 비어 하위 목록만 감싸는 행
            return true;
        }
        // item·body라는 이름의 실제 필드도 있어, 크기까지 비어야 감싸는 행으로 본다
        return wrapper && noSample && isEmptyCell(size);
    }

    /**
     * 항목구분·필수여부 칸이 필수를 뜻하는지 본다. 표준 표기는 1/0과 1..n/0..n이고,
     * 기관에 따라 필수/옵션, Y/N, O/X, ○ 로 적는다.
     */
    private boolean isRequired(String gubun) {
        String value = SpecLabels.normalize(gubun);
        return value.startsWith("1") || REQUIRED_MARKS.contains(value) || value.startsWith("필수");
    }

    /** 타입 열이 있는 표의 선언 타입 (소문자, 공백 제거). 없으면 빈 문자열 */
    private String declaredType(List<String> cells) {
        return cells.size() > FieldColumns.TYPE ? SpecLabels.normalize(cells.get(FieldColumns.TYPE)) : "";
    }

    /** resultCode·RESULT_CODE·result_code를 같은 이름으로 본다 */
    private String metaKey(String name) {
        return name.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private boolean isEmptyCell(String value) {
        String trimmed = value.strip();
        return trimmed.isEmpty() || EMPTY_CELL.equals(trimmed);
    }

    /**
     * reviewNotes가 null이면 이미 규칙으로 확정된 필드라 노트를 남기지 않는다.
     * 표에 타입 열이 있으면 그 선언을 따른다. 정수 선언이라도 샘플에 선행 0이 있으면 값이 바뀌므로
     * 문자열로 둔다. "number"처럼 정수·실수를 가리지 않는 선언은 샘플로 정한다.
     */
    private String inferType(String sample, String name, String description, String declared,
                             List<String> reviewNotes) {
        if (STRING_TYPE.matcher(declared).find()) {
            return "string";
        }
        if (BOOLEAN_TYPE.matcher(declared).matches()) {
            return "boolean";
        }
        if (INTEGER_TYPE.matcher(declared).find() && !LEADING_ZERO_SAMPLE.matcher(sample).matches()) {
            return "integer";
        }
        if (DECIMAL_TYPE.matcher(declared).find() && !LEADING_ZERO_SAMPLE.matcher(sample).matches()) {
            return "number";
        }
        if (isNonNumeric(name, description, sample)) {
            return "string";
        }
        return inferSample(sample, name, reviewNotes);
    }

    private String inferSample(String sample, String name, List<String> reviewNotes) {
        if (INTEGER_SAMPLE.matcher(sample).matches()) {
            // 규칙으로 확정한 필드까지 노트를 남기면 노트가 검토 신호 역할을 못 한다
            if (reviewNotes != null && !BODY_META_FIELDS.contains(metaKey(name))) {
                reviewNotes.add("샘플데이터 기반 integer 추론: " + name + " (코드형 문자열일 수 있음)");
            }
            return "integer";
        }
        if (NUMBER_SAMPLE.matcher(sample).matches()) {
            return "number";
        }
        return "string";
    }

    /**
     * 샘플이 숫자여도 산술 대상이 아닌 필드를 가려낸다. 법인등록번호·발표시각처럼
     * int 범위를 넘거나 선행 0이 의미를 갖는 값이 정수로 추론되는 것을 막는다.
     * 페이징 메타 필드는 이름 접미사 규칙(pageNo)에 걸리므로 먼저 제외한다.
     */
    private boolean isNonNumeric(String name, String description, String sample) {
        if (BODY_META_FIELDS.contains(metaKey(name))) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        return NON_NUMERIC_NAME_SUFFIXES.stream().anyMatch(lowerName::endsWith)
                || NON_NUMERIC_DESCRIPTION_KEYWORDS.stream().anyMatch(description::contains)
                || LEADING_ZERO_SAMPLE.matcher(sample).matches();
    }

    private ObjectNode errorSpec() {
        ObjectNode errorSpec = objectMapper.createObjectNode();
        errorSpec.put("successResultCode", SUCCESS_RESULT_CODE);
        ArrayNode candidates = errorSpec.putArray("resultCodeFieldCandidates");
        candidates.add("response.header.resultCode");
        candidates.add("resultCode");
        candidates.add("RESULT_CODE");
        candidates.add("cmmMsgHeader.returnReasonCode");
        return errorSpec;
    }

    private ObjectNode generatorHints(String apiId) {
        ObjectNode hints = objectMapper.createObjectNode();
        hints.put("targetClientType", "RestTemplate");
        hints.put("targetPackage", "kr.go.h2spec.client." + apiId.toLowerCase());
        hints.put("generateInterceptor", true);
        hints.put("generateDto", true);
        hints.put("dtoNamingStrategy", "PascalCase");
        return hints;
    }

    private ObjectNode metadata(String sourceFile, String sourceFormat, List<String> reviewNotes) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sourceFile", sourceFile);
        metadata.put("sourceFormat", sourceFormat);
        metadata.put("parsedAt", OffsetDateTime.now().toString());
        metadata.put("parserVersion", PARSER_VERSION);
        metadata.put("manualReviewRequired", !reviewNotes.isEmpty());
        ArrayNode notes = metadata.putArray("reviewNotes");
        reviewNotes.forEach(notes::add);
        return metadata;
    }
}
