package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 정답 파일 ({@code *.expected.json}).
 * <p>
 * {@code url}·{@code request}·{@code response}는 지금 corpus 테스트가 쓰는 키다. 나머지는 사람이 문서를 보고 앞으로 더할 키라
 * 아직 없을 수 있다. 없는 키는 null로 두어 그 키로 재는 지표만 건너뛴다. 정답 값을 여기서 짐작해 채우지 않는다.
 * 빈 값과 없는 키는 다르다. {@code "successCode": ""}는 "문서에 성공코드가 없다"는 정답이다.
 *
 * @param source     원본 문서 이름 (기관 배포 파일명)
 * @param operations 오퍼레이션 정답 (문서 순서)
 */
record GoldFile(String source, List<GoldOperation> operations) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 오퍼레이션 정답 하나. 키가 없으면 해당 필드는 null이다.
     *
     * @param name                  영문 오퍼레이션명. 짝짓기에 쓴다. 문서에 없으면 빈 문자열
     * @param title                 국문 기능명. 짝짓기에 쓴다
     * @param url                   오퍼레이션 주소 (baseUrl + endpoint)
     * @param request               요청 파라미터 이름. 파서가 채운 serviceKey·경로 변수도 들어 있다
     * @param response              응답 필드 이름 (경로의 마지막 칸)
     * @param httpMethod            문서의 HTTP 메서드 표기 (GET, POST, OTHER, UNKNOWN)
     * @param responseFormat        IR에 들어갈 응답 포맷 (XML, JSON)
     * @param successCode           llm 엔진의 성공코드 정책({@code SuccessCodeSelector})이 고를 성공코드. 문서에 성공코드가 없으면 빈 문자열
     * @param successCodeCandidates 문서 안에서 엇갈린 성공코드 후보의 값
     * @param auth                  인증 방식 (SERVICE_KEY_QUERY, PATH_KEY, HEADER_KEY …)
     * @param requestDetail         요청 파라미터의 위치·타입·필수 여부
     * @param responseDetail        응답 필드의 전체 경로와 타입. 경로는 IR 응답 포맷 기준이다
     * @param supportedByGenerator  generator v1이 이 오퍼레이션을 지원하는지
     */
    record GoldOperation(String name, String title, String url, List<String> request, List<String> response,
                         String httpMethod, String responseFormat, String successCode,
                         List<String> successCodeCandidates, String auth, List<GoldParameter> requestDetail,
                         List<GoldField> responseDetail, Boolean supportedByGenerator) {
    }

    /**
     * 요청 파라미터 정답.
     *
     * @param name     이름
     * @param in       위치 (query, path)
     * @param type     타입
     * @param required 필수 여부. 적지 않았으면 null
     */
    record GoldParameter(String name, String in, String type, Boolean required) {
    }

    /**
     * 응답 필드 정답.
     *
     * @param path 전체 경로 (response.body.items.item[].stationName, data[].baseDate)
     * @param type 타입. 적지 않았으면 null
     */
    record GoldField(String path, String type) {
    }

    /**
     * 정답 파일을 읽는다.
     *
     * @param file 정답 파일 경로
     * @return 정답
     * @throws IOException 파일을 읽지 못했거나 JSON이 아닐 때
     */
    static GoldFile read(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        List<GoldOperation> operations = new ArrayList<>();
        for (JsonNode op : root.path("operations")) {
            operations.add(new GoldOperation(
                    text(op, "name"),
                    text(op, "title"),
                    text(op, "url"),
                    texts(op.get("request")),
                    texts(op.get("response")),
                    text(op, "httpMethod"),
                    text(op, "responseFormat"),
                    text(op, "successCode"),
                    candidates(op.get("successCodeCandidates")),
                    auth(op.get("auth")),
                    parameters(op.get("requestDetail")),
                    fields(op.get("responseDetail")),
                    op.hasNonNull("supportedByGenerator") ? op.get("supportedByGenerator").asBoolean() : null));
        }
        return new GoldFile(text(root, "source"), operations);
    }

    private static String text(JsonNode node, String key) {
        return node.hasNonNull(key) ? node.get(key).asText() : null;
    }

    private static List<String> texts(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        array.forEach(item -> texts.add(item.asText()));
        return texts;
    }

    /** 후보는 {@code {code, source}} 객체로 적을 수도, 값만 적을 수도 있다 (형식을 하나로 정하지 않았다) */
    private static List<String> candidates(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<String> codes = new ArrayList<>();
        array.forEach(item -> codes.add(item.isObject() ? item.path("code").asText() : item.asText()));
        return codes;
    }

    /** 인증은 종류만 적을 수도, 중간 스키마의 LlmAuth처럼 {@code {kind, parameterName}}으로 적을 수도 있다 */
    private static String auth(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isObject() ? text(node, "kind") : node.asText();
    }

    private static List<GoldParameter> parameters(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<GoldParameter> parameters = new ArrayList<>();
        array.forEach(item -> parameters.add(new GoldParameter(text(item, "name"), text(item, "in"),
                text(item, "type"), item.hasNonNull("required") ? item.get("required").asBoolean() : null)));
        return parameters;
    }

    private static List<GoldField> fields(JsonNode array) {
        if (array == null || !array.isArray()) {
            return null;
        }
        List<GoldField> fields = new ArrayList<>();
        array.forEach(item -> fields.add(new GoldField(text(item, "path"), text(item, "type"))));
        return fields;
    }
}
