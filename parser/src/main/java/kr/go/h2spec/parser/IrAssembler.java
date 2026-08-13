package kr.go.h2spec.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 상세기능정보/요청/응답 표를 IR JSON(ObjectNode)으로 조립한다. */
public class IrAssembler {

    private static final String PARSER_VERSION = "0.1.0";
    private static final String OPERATION_PREFIX = "get";
    private static final Pattern INTEGER_SAMPLE = Pattern.compile("^\\d+$");
    private static final Pattern NUMBER_SAMPLE = Pattern.compile("^\\d+\\.\\d+$");
    /** 공공데이터 표준 응답에서 response.header 바로 아래에 오는 필드 */
    private static final Set<String> HEADER_FIELDS = Set.of("resultCode", "resultMsg");
    /** 공공데이터 표준 응답에서 response.body 바로 아래에 오는 페이징 메타 필드 */
    private static final Set<String> BODY_META_FIELDS = Set.of("numOfRows", "pageNo", "totalCount");
    private static final String SUCCESS_RESULT_CODE = "00";

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedApi assemble(String sourceFile, Map<String, String> info,
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
        api.put("responseFormat", "XML");
        api.set("requestParameters", requestParameters(requestRows, reviewNotes));
        api.set("responseFields", responseFields(responseRows, reviewNotes));
        api.set("errorSpec", errorSpec());

        ObjectNode ir = objectMapper.createObjectNode();
        ir.set("metadata", metadata(sourceFile, reviewNotes));
        ir.set("api", api);
        ir.set("generatorHints", generatorHints(apiId));
        return new ParsedApi(apiId, ir);
    }

    private String callBackUrl(Map<String, String> info) {
        return info.entrySet().stream()
                .filter(entry -> entry.getKey().contains("Call Back"))
                .map(entry -> entry.getValue().replaceAll("\\s+", ""))
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("상세기능정보 표에 Call Back URL이 없습니다"));
    }

    /** getMsrstnList → MsrstnList (get 접두사 제거, 첫 글자 대문자) */
    private String toApiId(String segment) {
        String name = segment.startsWith(OPERATION_PREFIX)
                ? segment.substring(OPERATION_PREFIX.length())
                : segment;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private ArrayNode requestParameters(List<List<String>> rows, List<String> reviewNotes) {
        ArrayNode parameters = objectMapper.createArrayNode();
        for (List<String> cells : dataRows(rows)) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String gubun = cells.get(3);
            String sample = cells.get(4);
            String description = cells.get(5);

            ObjectNode param = parameters.addObject();
            param.put("name", name);
            param.put("in", "query");
            param.put("type", inferType(sample, name, reviewNotes));
            param.put("required", gubun.startsWith("1"));
            param.put("description", description.isBlank() ? korName : description);
            if (!sample.isBlank() && !"-".equals(sample)) {
                param.put("example", sample);
            }
        }
        return parameters;
    }

    private ArrayNode responseFields(List<List<String>> rows, List<String> reviewNotes) {
        ArrayNode fields = objectMapper.createArrayNode();
        for (List<String> cells : dataRows(rows)) {
            String name = cells.get(0);
            String korName = cells.get(1);
            String sample = cells.get(4);
            String description = cells.get(5);

            ObjectNode field = fields.addObject();
            field.put("path", pathFor(name));
            // 결과코드/메시지는 샘플이 숫자("00")여도 선행 0 보존을 위해 항상 문자열
            field.put("type", HEADER_FIELDS.contains(name)
                    ? "string"
                    : inferType(sample, name, reviewNotes));
            field.put("description", description.isBlank() ? korName : description);
            if ("resultCode".equals(name)) {
                field.put("isResultIndicator", true);
            }
        }
        return fields;
    }

    /** 표에는 계층 정보가 없어 공공데이터 표준 응답 구조(header/body/items)를 가정한다. */
    private String pathFor(String name) {
        if (HEADER_FIELDS.contains(name)) {
            return "response.header." + name;
        }
        if (BODY_META_FIELDS.contains(name)) {
            return "response.body." + name;
        }
        return "response.body.items.item[]." + name;
    }

    /** 헤더 행과 셀 수가 모자란 행(각주 등)을 걸러낸 데이터 행만 반환 */
    private List<List<String>> dataRows(List<List<String>> rows) {
        return rows.stream()
                .filter(cells -> cells.size() >= 6)
                .filter(cells -> !cells.get(0).isBlank())
                .filter(cells -> !cells.get(0).contains("항목명"))
                .toList();
    }

    private String inferType(String sample, String name, List<String> reviewNotes) {
        if (INTEGER_SAMPLE.matcher(sample).matches()) {
            reviewNotes.add("샘플데이터 기반 integer 추론: " + name + " (코드형 문자열일 수 있음)");
            return "integer";
        }
        if (NUMBER_SAMPLE.matcher(sample).matches()) {
            return "number";
        }
        return "string";
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

    private ObjectNode metadata(String sourceFile, List<String> reviewNotes) {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("sourceFile", sourceFile);
        metadata.put("sourceFormat", "DOCX");
        metadata.put("parsedAt", OffsetDateTime.now().toString());
        metadata.put("parserVersion", PARSER_VERSION);
        metadata.put("manualReviewRequired", !reviewNotes.isEmpty());
        ArrayNode notes = metadata.putArray("reviewNotes");
        reviewNotes.forEach(notes::add);
        return metadata;
    }
}
