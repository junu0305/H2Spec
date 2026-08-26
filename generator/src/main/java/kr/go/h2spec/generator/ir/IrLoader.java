package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class IrLoader {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public IrSpec load(Path irJson) throws IOException {
        IrSpec ir = objectMapper.readValue(irJson.toFile(), IrSpec.class);
        validate(ir);
        return ir;
    }

    private void validate(IrSpec ir) {
        List<String> missing = new ArrayList<>();
        if (ir.api() == null) {
            missing.add("api");
        } else {
            if (isBlank(ir.api().apiId())) {
                missing.add("api.apiId");
            }
            if (isBlank(ir.api().baseUrl())) {
                missing.add("api.baseUrl");
            }
            if (isBlank(ir.api().endpoint())) {
                missing.add("api.endpoint");
            }
            if (ir.api().responseFields().isEmpty()) {
                missing.add("api.responseFields");
            }
            if (ir.api().errorSpec() == null || isBlank(ir.api().errorSpec().successResultCode())) {
                missing.add("api.errorSpec.successResultCode");
            }
        }
        if (ir.generatorHints() == null || isBlank(ir.generatorHints().targetPackage())) {
            missing.add("generatorHints.targetPackage");
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("IR 필수 필드 누락: " + String.join(", ", missing));
        }

        validateEndpointFormat(ir.api());
        validateV1Scope(ir.api());
    }

    // 엔드포인트는 OpenAPI path 형식의 '/'로 시작해야 한다.
    private void validateEndpointFormat(ApiSpec api) {
        String endpoint = api.endpoint();
        if (!endpoint.startsWith("/")) {
            throw new IllegalArgumentException(
                    "api.endpoint 형식이 잘못되었습니다: '" + endpoint + "' — '/'로 시작해야 합니다.");
        }
        for (RequestParameter param : api.requestParameters()) {
            if ("path".equals(param.in()) && !endpoint.contains("{" + param.name() + "}")) {
                throw new IllegalArgumentException(
                        "path 파라미터가 endpoint에 없습니다: " + param.name());
            }
        }
    }

    // v1 스코프 가드: GET/path·query 파라미터/XML|JSON 응답만 지원한다.
    // ClientEmitter가 실제로 처리하지 못하는 조합이 조용히 잘못된 요청/파싱으로 이어지지 않도록 로드 시점에 막는다.
    private void validateV1Scope(ApiSpec api) {
        if (!"GET".equals(api.httpMethod())) {
            throw new IllegalArgumentException(
                    "v1은 GET만 지원합니다 (api.httpMethod=" + api.httpMethod() + ")");
        }
        for (RequestParameter param : api.requestParameters()) {
            if (!"query".equals(param.in()) && !"path".equals(param.in())) {
                throw new IllegalArgumentException(
                        "미지원 파라미터 위치: in=" + param.in() + " (name=" + param.name() + ")");
            }
            if ("path".equals(param.in()) && !param.required()) {
                throw new IllegalArgumentException(
                        "path 파라미터는 required=true여야 합니다 (name=" + param.name() + ")");
            }
        }
        if (!"XML".equals(api.responseFormat()) && !"JSON".equals(api.responseFormat())) {
            throw new IllegalArgumentException("미지원 responseFormat: " + api.responseFormat());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
