package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

/**
 * 테스트용 IR. 기본값은 generator가 받는 최소 IR이다 (게이트웨이 주소, serviceKey, 표준 경로의 응답 필드 하나, 성공코드 00).
 */
final class IrBuilder {

    private final ObjectNode ir = JsonNodeFactory.instance.objectNode();
    private final ObjectNode api;

    /**
     * @param apiId    apiId. 패키지 이름도 이것으로 만든다
     * @param baseUrl  baseUrl
     * @param endpoint endpoint
     */
    IrBuilder(String apiId, String baseUrl, String endpoint) {
        ir.putObject("metadata").putArray("reviewNotes");
        api = ir.putObject("api");
        api.put("apiId", apiId);
        api.put("apiName", apiId);
        api.put("description", "");
        api.put("baseUrl", baseUrl);
        api.put("endpoint", endpoint);
        api.put("httpMethod", "GET");
        api.put("authType", "SERVICE_KEY_QUERY_PARAM");
        api.put("responseFormat", "XML");
        api.putArray("requestParameters");
        api.putArray("responseFields");
        api.putObject("errorSpec").put("successResultCode", "00");
        ir.putObject("generatorHints").put("targetPackage", "kr.go.h2spec.client." + apiId.toLowerCase(Locale.ROOT));
    }

    /** 게이트웨이 주소의 오퍼레이션. serviceKey와 표준 경로의 필드 하나를 담는다 */
    static IrBuilder gateway(String operation) {
        return new IrBuilder(Character.toUpperCase(operation.charAt(3)) + operation.substring(4),
                "http://apis.data.go.kr/1234567/TestService", "/" + operation)
                .param("serviceKey", "query", true)
                .field("response.body.items.item[].stationName", "string");
    }

    IrBuilder param(String name, String in, boolean required) {
        ObjectNode param = api.withArray("/requestParameters").addObject();
        param.put("name", name);
        param.put("in", in);
        param.put("type", "string");
        param.put("required", required);
        param.put("description", name);
        return this;
    }

    IrBuilder field(String path, String type) {
        ObjectNode field = api.withArray("/responseFields").addObject();
        field.put("path", path);
        field.put("type", type);
        field.put("description", path);
        return this;
    }

    IrBuilder format(String format) {
        api.put("responseFormat", format);
        return this;
    }

    IrBuilder successCode(String code) {
        api.withObject("/errorSpec").put("successResultCode", code);
        return this;
    }

    IrBuilder note(String note) {
        ir.withArray("/metadata/reviewNotes").add(note);
        return this;
    }

    IrBuilder auth(String kind, String parameterName) {
        ObjectNode auth = api.putObject("auth");
        auth.put("kind", kind);
        auth.put("parameterName", parameterName);
        return this;
    }

    IrBuilder remove(String apiField) {
        api.remove(apiField);
        return this;
    }

    ObjectNode build() {
        return ir.deepCopy();
    }

    /** IR을 낸 엔진 오퍼레이션 */
    EngineOperation operation(String operationName, String title) {
        return new EngineOperation(operationName, title, build(), null, false);
    }
}
