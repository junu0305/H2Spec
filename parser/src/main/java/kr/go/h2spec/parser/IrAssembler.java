package kr.go.h2spec.parser;

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
     * 항목구분 칸에 카디널리티(예: 0..n, 1..1)가 적힌 행은 실제 필드가 아니라
     * items처럼 하위 표를 감싸는 컨테이너 행이다.
     */
    private static final Pattern CONTAINER_CARDINALITY = Pattern.compile("^\\d+\\.\\.(\\d+|n|N)$");
    /**
     * 산술 대상이 아닌 식별자·코드·일자를 가리키는 이름 접미사 (소문자 비교).
     * "tm"은 거리(tm)·item처럼 시각이 아닌 이름까지 걸리므로 넣지 않고 설명 키워드에 맡긴다.
     */
    private static final List<String> NON_NUMERIC_NAME_SUFFIXES =
            List.of("cd", "code", "no", "id", "dt", "ymd");
    /** 산술 대상이 아님을 드러내는 항목설명 키워드 */
    private static final List<String> NON_NUMERIC_DESCRIPTION_KEYWORDS =
            List.of("코드", "번호", "일자", "년월일", "시각", "일시");
    /** 공공데이터 표준 응답에서 response.header 바로 아래에 오는 필드 */
    private static final Set<String> HEADER_FIELDS = Set.of("resultCode", "resultMsg");
    /** 공공데이터 표준 응답에서 response.body 바로 아래에 오는 페이징 메타 필드 */
    private static final Set<String> BODY_META_FIELDS = Set.of("numOfRows", "pageNo", "totalCount");
    /**
     * 요청에 대응 파라미터가 없어도 body 메타로 보는 응답 전용 필드.
     * 전체 건수는 서버가 계산해 내려주므로 요청 파라미터에 나타나지 않는다.
     */
    private static final Set<String> RESPONSE_ONLY_META_FIELDS = Set.of("totalCount");
    /** 페이징 메타로 볼 만한 이름꼴. 이름만으로는 부족해 요청 파라미터 존재 여부와 함께 본다 */
    private static final Pattern PAGING_NAME = Pattern.compile("(cnt|count|rows)$|^page(No|Index)$|^currentPage$",
            Pattern.CASE_INSENSITIVE);
    /** 응답 포맷을 고르는 요청 파라미터. 이름이 기관마다 다르다 */
    private static final Set<String> RESPONSE_FORMAT_PARAMS = Set.of("returnType", "dataType", "resultType");
    private static final String XML_FORMAT = "XML";
    private static final String JSON_FORMAT = "JSON";
    private static final String SUCCESS_RESULT_CODE = "00";
    /** Call Back URL 칸을 비워두는 대신 적어두는 값 */
    private static final String NOT_AVAILABLE = "N/A";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedApi assemble(String sourceFile, String sourceFormat, Map<String, String> info,
                              List<List<String>> requestRows, List<List<String>> responseRows) {
        String url = callBackUrl(info);
        int lastSlash = url.lastIndexOf('/');
        String segment = url.substring(lastSlash + 1);
        String baseUrl = url.substring(0, lastSlash);
        String apiId = toApiId(segment);

        List<String> reviewNotes = new ArrayList<>();

        ObjectNode api = objectMapper.createObjectNode();
        api.put("apiId", apiId);
        api.put("apiName", info.getOrDefault("상세기능명(국문)", apiId));
        api.put("provider", "");
        api.put("baseUrl", baseUrl);
        api.put("endpoint", "/" + segment);
        api.put("httpMethod", "GET");
        api.put("description", info.getOrDefault("상세기능 설명", ""));
        api.put("authType", "SERVICE_KEY_QUERY_PARAM");
        api.put("responseFormat", responseFormat(requestRows));
        api.set("requestParameters", requestParameters(requestRows, reviewNotes));
        api.set("responseFields", responseFields(responseRows, requestParamNames(requestRows), reviewNotes));
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
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private ArrayNode requestParameters(List<List<String>> rows, List<String> reviewNotes) {
        ArrayNode parameters = objectMapper.createArrayNode();
        for (List<String> cells : dataRows(rows)) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String gubun = cells.get(3);
            String sample = cells.get(4);
            String description = describe(cells.get(5), korName);

            ObjectNode param = parameters.addObject();
            param.put("name", name);
            param.put("in", "query");
            param.put("type", inferType(sample, name, description, reviewNotes));
            param.put("required", gubun.startsWith("1"));
            param.put("description", description);
            if (!sample.isBlank() && !"-".equals(sample)) {
                param.put("example", sample);
            }
        }
        return parameters;
    }

    private Set<String> requestParamNames(List<List<String>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (List<String> cells : dataRows(rows)) {
            names.add(cells.get(0));
        }
        return names;
    }

    private ArrayNode responseFields(List<List<String>> rows, Set<String> requestParamNames,
                                     List<String> reviewNotes) {
        ArrayNode fields = objectMapper.createArrayNode();
        for (List<String> cells : dataRows(rows)) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String sample = cells.get(4);
            String description = describe(cells.get(5), korName);

            ObjectNode field = fields.addObject();
            field.put("path", pathFor(name, requestParamNames));
            // 결과코드/메시지는 샘플이 숫자("00")여도 선행 0 보존을 위해 항상 문자열
            field.put("type", HEADER_FIELDS.contains(name)
                    ? "string"
                    : inferType(sample, name, description, reviewNotes));
            field.put("description", description);
            if ("resultCode".equals(name)) {
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
        for (List<String> cells : dataRows(requestRows)) {
            if (RESPONSE_FORMAT_PARAMS.contains(cells.get(0)) && JSON_FORMAT.equalsIgnoreCase(cells.get(4))) {
                return JSON_FORMAT;
            }
        }
        return XML_FORMAT;
    }

    /** 항목설명이 비어 있으면 국문 항목명을 설명으로 쓴다. */
    private String describe(String description, String korName) {
        return description.isBlank() ? korName : description;
    }

    /** 표에는 계층 정보가 없어 공공데이터 표준 응답 구조(header/body/items)를 가정한다. */
    private String pathFor(String name, Set<String> requestParamNames) {
        if (HEADER_FIELDS.contains(name)) {
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
        if (BODY_META_FIELDS.contains(name) || RESPONSE_ONLY_META_FIELDS.contains(name)) {
            return true;
        }
        return PAGING_NAME.matcher(name).find() && requestParamNames.contains(name);
    }

    /**
     * 헤더 행, 셀 수가 모자란 행(각주 등), items처럼 하위 표를 감싸기만 하는
     * 컨테이너 행을 걸러낸 실제 데이터 행만 반환한다.
     */
    private List<List<String>> dataRows(List<List<String>> rows) {
        return rows.stream()
                .map(this::unindent)
                .filter(cells -> cells.size() >= 6)
                .filter(cells -> !cells.get(0).isBlank())
                .filter(cells -> !cells.get(0).contains("항목명"))
                .filter(cells -> !isContainerRow(cells))
                .toList();
    }

    /**
     * 컨테이너 아래 자식 행을 앞 칸을 비워 들여쓴 문서가 있다. 한 칸 밀린 행은
     * 앞의 빈 칸을 떼어 다른 행과 같은 열 구성으로 맞춘다.
     */
    private List<String> unindent(List<String> cells) {
        return cells.size() > 6 && cells.get(0).isBlank() ? cells.subList(1, cells.size()) : cells;
    }

    /** 항목구분(예: "0..n")이 카디널리티 표기이면 실제 필드가 아닌 컨테이너 행이다. */
    private boolean isContainerRow(List<String> cells) {
        return CONTAINER_CARDINALITY.matcher(cells.get(3).trim()).matches();
    }

    private String inferType(String sample, String name, String description, List<String> reviewNotes) {
        if (isNonNumeric(name, description, sample)) {
            return "string";
        }
        if (INTEGER_SAMPLE.matcher(sample).matches()) {
            // 규칙으로 확정한 필드까지 노트를 남기면 노트가 검토 신호 역할을 못 한다
            if (!BODY_META_FIELDS.contains(name)) {
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
        if (BODY_META_FIELDS.contains(name)) {
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
