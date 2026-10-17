package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** 상세기능정보/요청/응답 표를 IR JSON(ObjectNode)으로 조립한다. */
public class IrAssembler {

    private static final Pattern INTEGER_SAMPLE = Pattern.compile("^\\d+$");
    private static final Pattern NUMBER_SAMPLE = Pattern.compile("^\\d+\\.\\d+$");
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
    private static final String XML_FORMAT = "XML";
    private static final String JSON_FORMAT = "JSON";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedApi assemble(String sourceFile, String sourceFormat, Map<String, String> info,
                              List<List<String>> requestRows, List<List<String>> responseRows) {
        return assemble(sourceFile, sourceFormat, info, requestRows, responseRows, DocumentExamples.none(), null);
    }

    /**
     * @param examples       오퍼레이션 구간의 응답·요청 예제. 성공코드, 응답 구조, 요청 표에 빠진 인증키를 여기서 찾는다
     * @param errorTableCode 문서 에러 코드 표의 정상 행 코드. 없으면 null
     */
    ParsedApi assemble(String sourceFile, String sourceFormat, Map<String, String> info,
                       List<List<String>> requestRows, List<List<String>> responseRows,
                       DocumentExamples examples, String errorTableCode) {
        IrConventions.UrlParts url = splitCallBackUrl(info);
        if (!url.ok()) {
            throw new IllegalArgumentException(url.error());
        }
        String apiId = url.apiId();

        ReviewNotes reviewNotes = new ReviewNotes();
        String assemblyNote = info.get(SpecBlockAssembler.REVIEW_NOTE_KEY);
        if (assemblyNote != null) {
            for (String note : assemblyNote.split("\n")) {
                reviewNotes.add(note);
            }
        }
        List<List<String>> requests = dataRows(requestRows);
        List<List<String>> responses = dataRows(responseRows);
        Set<String> requestParamNames = namesOf(requests);

        ObjectNode api = objectMapper.createObjectNode();
        String description = infoValue(info, SpecLabels.operationDescriptionKeys());
        api.put("apiId", apiId);
        api.put("apiName", IrConventions.apiName(infoValue(info, SpecLabels.operationTitleKeys()), apiId));
        api.put("provider", "");
        api.put("baseUrl", url.baseUrl());
        api.put("endpoint", url.endpoint());
        api.put("httpMethod", "GET");
        api.put("description", IrText.oneLine(description == null ? "" : description));
        // api.auth는 적지 않는다. generator가 api.auth 없이도 요청 파라미터 이름으로 키 자리를 찾는다(serviceKey, apiKey·authApiKey 같은
        // 질의 파라미터, {your_appkey} 같은 경로 변수). 요청 표에 키가 없으면 요청 예제 주소에서 찾아 넣는다(exampleAuthKey).
        // TODO(인증 확장): 헤더로 보내는 키는 요청 표에 없어 찾지 못한다
        api.put("authType", "SERVICE_KEY_QUERY_PARAM");
        String format = responseFormat(requests);
        Optional<ExampleStructure.Structure> structure =
                exampleStructure(responses, requests, requestParamNames, format, examples);
        if (structure.isPresent() && !structure.get().format().equals(format)) {
            format = structure.get().format();
            reviewNotes.add("요청에 응답 형식 파라미터가 없고 응답 예제가 " + format + "라 응답 형식을 " + format + "로 정함");
        }
        api.put("responseFormat", format);
        if (structure.isPresent() && structure.get().rootless()) {
            api.put("responseRootless", true);
        }
        ArrayNode parameters = requestParameters(requests, requestParamNames, reviewNotes);
        if (info.containsKey(SpecBlockAssembler.GATEWAY_SERVICE_KEY_FLAG)) {
            // 경로 변수 파라미터보다 먼저 넣어야 경로 변수 다음 자리가 된다
            IrConventions.insertGatewayServiceKey(parameters);
        } else {
            exampleAuthKey(parameters, url, examples, reviewNotes);
        }
        IrConventions.addPathParameters(parameters, url.endpoint());
        api.set("requestParameters", parameters);
        if (structure.isPresent()) {
            ExampleStructure.Structure found = structure.get();
            reviewNotes.add("응답 필드 표에 계층이 없어 응답 예제의 구조로 응답 필드 경로를 정함"
                    + (found.rootless() ? " (루트 없는 JSON 응답)" : " (루트 " + firstSegment(found.paths()) + ")"));
            found.renamed().forEach((from, to) ->
                    reviewNotes.add("응답 예제의 표기로 응답 필드 이름을 고침: " + from + " → " + to));
        }
        api.set("responseFields", responseFields(responses, requestParamNames, reviewNotes,
                structure.map(ExampleStructure.Structure::paths).orElse(Map.of())));
        api.set("errorSpec", errorSpec(examples, errorTableCode, reviewNotes));

        ObjectNode ir = objectMapper.createObjectNode();
        ir.set("metadata", IrConventions.metadata(sourceFile, sourceFormat, reviewNotes.lines()));
        ir.set("api", api);
        // TODO(rule 포맷 파라미터): rule 엔진은 generatorHints.responseFormatParameter를 적지 않는다. 그래서 포맷 파라미터 이름이
        //   generator가 아는 returnType·dataType·resultType(IrConventions.responseFormatParameters)이 아니면 생성 클라이언트가
        //   그 파라미터에 값을 채우지 않는다. 선택 파라미터면 보내지 않아 서버 기본 형식이 오고, 필수 파라미터면 메서드 인자로
        //   나와 호출하는 쪽이 값을 넣는데, IR의 responseFormat과 다른 값을 넣으면 생성 DTO가 응답을 읽지 못한다.
        //   샘플 문서: its-traffic-light(type, 선택), khoa-realtime-sea-current(type, 필수. IR은 XML인데 문서의 예시 값은 json),
        //   mohw-emergency-safety-org(apiType), docs/sample의 천문연 특일 정보(_type).
        //   고칠 것: llm 엔진(IrNormalizer.hintedFormatParameter·formatValue)처럼 요청 표에서 포맷 파라미터를 찾아
        //   {name, value}를 적는다. 값은 responseFormat이고 대소문자는 표의 샘플·기본값을 따른다. responseFormat 판단
        //   (아래 responseFormat)도 같은 파라미터를 보게 한다. 관련 자리는 "TODO(rule 포맷 파라미터)"로 찾는다.
        ir.set("generatorHints", IrConventions.generatorHints(apiId));
        return new ParsedApi(apiId, ir);
    }

    /**
     * SpecBlockAssembler가 정한 주소를 나눈다. 정한 주소 없이 정보 표만 넘긴 직접 호출은 정보 표의
     * 주소 칸을 그대로 나눈다. 이때는 끝 슬래시를 지우지 않고 오퍼레이션 경로가 없는 주소로 알린다(이전 동작).
     */
    private IrConventions.UrlParts splitCallBackUrl(Map<String, String> info) {
        String resolved = info.get(SpecBlockAssembler.ENDPOINT_URL_KEY);
        if (resolved != null && !resolved.isBlank()) {
            return IrConventions.splitUrl(resolved);
        }
        return IrConventions.splitUntrimmed(callBackUrl(info));
    }

    /**
     * Call Back URL을 우선 쓰고, 없거나 N/A인 문서는 서비스 개요 표의 주소와
     * 오퍼레이션명(영문)으로 조립한다. 천문연구원 특일정보처럼 Call Back URL 칸을
     * N/A로 비워두고 주소를 별도 표에 적는 문서가 있다.
     */
    private String callBackUrl(Map<String, String> info) {
        String callBack = info.entrySet().stream()
                .filter(entry -> entry.getKey().contains("Call Back"))
                .map(entry -> entry.getValue().replaceAll("\\s+", ""))
                .filter(value -> !value.isBlank() && !IrConventions.NOT_AVAILABLE.equalsIgnoreCase(value))
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
     * 정보 표에서 키 후보 순서대로 국문명·설명 자리에 쓸 수 있는 첫 값을 찾는다. 키는 공백을 뺀 꼴로 비교한다
     * ("상세기능 설명"과 "상세기능설명", "오퍼레이션 설명"을 같은 자리로 본다).
     * 영문 식별자만 담긴 값(getXxx)은 국문명 자리에 맞지 않아 건너뛴다({@link IrConventions#isDisplayText}).
     *
     * @return 앞뒤 공백을 지운 값. 없으면 null
     */
    static String infoValue(Map<String, String> info, List<String> keys) {
        for (String key : keys) {
            for (Map.Entry<String, String> entry : info.entrySet()) {
                if (SpecLabels.normalize(entry.getKey()).equals(key) && IrConventions.isDisplayText(entry.getValue())) {
                    return entry.getValue().strip();
                }
            }
        }
        return null;
    }

    private ArrayNode requestParameters(List<List<String>> rows, Set<String> requestParamNames,
                                        ReviewNotes reviewNotes) {
        ArrayNode parameters = objectMapper.createArrayNode();
        for (List<String> cells : rows) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String gubun = cells.get(3);
            String sample = cells.get(4);
            String description = IrConventions.description(cells.get(5), korName);

            ObjectNode param = parameters.addObject();
            boolean settled = isBodyMeta(name, requestParamNames);
            param.put("name", name);
            param.put("in", "query");
            param.put("type", inferType(sample, name, description, declaredType(cells), settled ? null : reviewNotes));
            param.put("required", isRequired(gubun));
            param.put("description", description);
            if (!sample.isBlank() && !"-".equals(sample)) {
                param.put("example", IrText.oneLine(sample));
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

    /**
     * 응답 예제로 응답 필드의 경로를 정한다({@link ExampleStructure}). 예제가 IR의 형식과 같아야 하고, 요청에 응답 형식 파라미터가
     * 없을 때만 다른 형식의 예제도 본다. 그때는 서버의 기본 형식으로 오고 예제가 그 형식을 보여 준다.
     */
    private Optional<ExampleStructure.Structure> exampleStructure(List<List<String>> responses,
                                                                  List<List<String>> requests,
                                                                  Set<String> requestParamNames, String format,
                                                                  DocumentExamples examples) {
        List<String> names = responses.stream().map(cells -> cells.get(0)).toList();
        Map<String, String> standard = new LinkedHashMap<>();
        for (String name : names) {
            standard.put(name, pathFor(name, requestParamNames));
        }
        boolean formatParameter = requests.stream()
                .anyMatch(cells -> IrConventions.responseFormatParameters().contains(cells.get(0)));
        List<DocumentExamples.Example.Format> formats = new ArrayList<>();
        formats.add(DocumentExamples.Example.Format.valueOf(format));
        if (!formatParameter) {
            formats.add(JSON_FORMAT.equals(format) ? DocumentExamples.Example.Format.XML : DocumentExamples.Example.Format.JSON);
        }
        return ExampleStructure.resolve(names, standard, formats, examples);
    }

    private static String firstSegment(Map<String, String> paths) {
        String path = paths.values().iterator().next();
        return path.substring(0, path.indexOf('.'));
    }

    /**
     * 성공코드. 정상 응답 예제의 결과코드, 에러 코드 표의 정상 행 코드, 기본값 00 순으로 정한다.
     * 0으로만 된 코드는 00으로 적는다(interceptor가 자릿수를 가리지 않고 비교한다).
     */
    private ObjectNode errorSpec(DocumentExamples examples, String errorTableCode, ReviewNotes reviewNotes) {
        ObjectNode errorSpec = IrConventions.errorSpec();
        Optional<String> fromExample = examples.successCode();
        String code = fromExample.orElse(errorTableCode);
        if (code == null || code.matches("0+")) {
            return errorSpec;
        }
        errorSpec.put("successResultCode", code);
        reviewNotes.add(fromExample.isPresent()
                ? "응답 예제의 정상 응답 결과코드 " + code + "를 성공코드로 씀"
                : "에러 코드 표의 정상 처리 코드 " + code + "를 성공코드로 씀");
        return errorSpec;
    }

    /**
     * 요청 표에 인증키 파라미터가 없으면 요청 예제 주소에서 찾아 맨 앞에 넣는다. 요청 표가 인증키 행을 빼고 예제 주소에만 적는 문서가 있다.
     * 경로 변수의 인증키({your_appkey})는 주소에 있어 따로 넣지 않는다.
     */
    private void exampleAuthKey(ArrayNode parameters, IrConventions.UrlParts url, DocumentExamples examples,
                                ReviewNotes reviewNotes) {
        for (var parameter : parameters) {
            if (IrConventions.isAuthKeyName(parameter.path("name").asText())) {
                return;
            }
        }
        if (IrConventions.hasPathAuthKey(url.endpoint())) {
            return;
        }
        for (String name : examples.exampleQueryNames(url.baseUrl() + url.endpoint())) {
            if (IrConventions.isAuthKeyName(name)) {
                ObjectNode param = parameters.objectNode();
                param.put("name", name);
                param.put("in", "query");
                param.put("type", "string");
                param.put("required", true);
                param.put("description", "인증키");
                parameters.insert(0, param);
                reviewNotes.add("요청 명세 표에 인증키가 없어 요청 예제 주소의 " + name + "를 인증키 파라미터로 추가함");
                return;
            }
        }
    }

    private ArrayNode responseFields(List<List<String>> rows, Set<String> requestParamNames,
                                     ReviewNotes reviewNotes, Map<String, String> examplePaths) {
        ArrayNode fields = objectMapper.createArrayNode();
        for (List<String> cells : rows) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String sample = cells.get(4);
            String description = IrConventions.description(cells.get(5), korName);

            ObjectNode field = fields.addObject();
            field.put("path", examplePaths.getOrDefault(name, pathFor(name, requestParamNames)));
            // 결과코드/메시지는 샘플이 숫자("00")여도 선행 0 보존을 위해 항상 문자열
            boolean settled = isBodyMeta(name, requestParamNames);
            field.put("type", IrConventions.isResultCodeOrMessageField(name)
                    ? "string"
                    : inferType(sample, name, description, declaredType(cells), settled ? null : reviewNotes));
            field.put("description", description);
            if (IrConventions.isResultCodeField(name)) {
                field.put("isResultIndicator", true);
            }
        }
        return fields;
    }

    /**
     * 응답 포맷은 포맷 파라미터의 샘플데이터(문서가 적어둔 기본값)로 정한다.
     * 항목설명 문구는 "xml 또는json", "결과형식(xml/json)"처럼 기관마다 달라 근거로 삼기 어렵다.
     */
    // TODO(rule 포맷 파라미터): generator가 아는 이름의 포맷 파라미터만 본다. 자세한 내용은 assemble의 generatorHints 자리
    private String responseFormat(List<List<String>> requestRows) {
        for (List<String> cells : requestRows) {
            if (IrConventions.responseFormatParameters().contains(cells.get(0))
                    && JSON_FORMAT.equalsIgnoreCase(cells.get(4).strip())) {
                return JSON_FORMAT;
            }
        }
        return XML_FORMAT;
    }

    /**
     * 표에는 계층 정보가 없어 공공데이터 표준 응답 구조(header/body/items)를 가정한다. 응답 예제가 있으면 예제의 구조를 쓴다
     * ({@link ExampleStructure}). 루트가 다르거나(rfcOpenApi) 루트 없는 JSON 응답(kosha의 {"header", "body"})도 거기서 정한다.
     * 예제가 없거나 표의 필드가 예제에서 절반도 찾아지지 않을 때만 이 표준 경로를 쓴다
     */
    private String pathFor(String name, Set<String> requestParamNames) {
        if (IrConventions.isResultCodeOrMessageField(name)) {
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
        String key = IrConventions.metaKey(name);
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
            // 샘플 칸에 값 대신 하위 구조 도식("“resultList”:[{“meta”: {...}")을 적은 행
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
     * 기관에 따라 필수/옵션, Y/N, O/X, ○로 적는다.
     */
    private boolean isRequired(String gubun) {
        String value = SpecLabels.normalize(gubun);
        return value.startsWith("1") || REQUIRED_MARKS.contains(value) || value.startsWith("필수");
    }

    /** 타입 열이 있는 표의 선언 타입 (소문자, 공백 제거). 없으면 빈 문자열 */
    private String declaredType(List<String> cells) {
        return cells.size() > FieldColumns.TYPE ? SpecLabels.normalize(cells.get(FieldColumns.TYPE)) : "";
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
                             ReviewNotes reviewNotes) {
        if (STRING_TYPE.matcher(declared).find()) {
            return "string";
        }
        if (BOOLEAN_TYPE.matcher(declared).matches()) {
            return "boolean";
        }
        if (INTEGER_TYPE.matcher(declared).find() && !IrConventions.hasLeadingZero(sample)) {
            return "integer";
        }
        if (DECIMAL_TYPE.matcher(declared).find() && !IrConventions.hasLeadingZero(sample)) {
            return "number";
        }
        if (isNonNumeric(name, description, sample)) {
            return "string";
        }
        return inferSample(sample, name, reviewNotes);
    }

    private String inferSample(String sample, String name, ReviewNotes reviewNotes) {
        if (INTEGER_SAMPLE.matcher(sample).matches()) {
            // 규칙으로 확정한 필드까지 노트를 남기면 노트가 검토 신호 역할을 못 한다
            if (reviewNotes != null && !BODY_META_FIELDS.contains(IrConventions.metaKey(name))) {
                reviewNotes.addField(ReviewNotes.FieldNote.SAMPLE_INTEGER, name);
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
        if (BODY_META_FIELDS.contains(IrConventions.metaKey(name))) {
            return false;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        return NON_NUMERIC_NAME_SUFFIXES.stream().anyMatch(lowerName::endsWith)
                || NON_NUMERIC_DESCRIPTION_KEYWORDS.stream().anyMatch(description::contains)
                || IrConventions.hasLeadingZero(sample);
    }
}
