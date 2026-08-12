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

        validateEndpointFormat(ir.api().endpoint());
        validateV1Scope(ir.api());
    }

    // v1은 '/'로 시작하고 세그먼트가 하나뿐인 엔드포인트만 지원한다.
    // 예: "/getFoo"는 OK, "/v1/getFoo"(세그먼트 2개)나 "getFoo"(선행 '/' 없음)는 거부한다.
    private void validateEndpointFormat(String endpoint) {
        if (!endpoint.startsWith("/")) {
            throw new IllegalArgumentException(
                    "api.endpoint 형식이 잘못되었습니다: '" + endpoint + "' — '/'로 시작해야 합니다.");
        }
        if (endpoint.indexOf('/', 1) != -1) {
            throw new IllegalArgumentException(
                    "api.endpoint 형식이 잘못되었습니다: '" + endpoint
                    + "' — 세그먼트가 하나여야 합니다 (추가 '/'를 포함할 수 없습니다).");
        }
    }

    // v1 스코프 가드: GET/query 파라미터/XML|JSON 응답만 지원한다.
    // ClientEmitter가 실제로 처리하지 못하는 조합이 조용히 잘못된 요청/파싱으로 이어지지 않도록 로드 시점에 막는다.
    private void validateV1Scope(ApiSpec api) {
        if (!"GET".equals(api.httpMethod())) {
            throw new IllegalArgumentException(
                    "v1은 GET만 지원합니다 (api.httpMethod=" + api.httpMethod() + ")");
        }
        for (RequestParameter param : api.requestParameters()) {
            if (!"query".equals(param.in())) {
                throw new IllegalArgumentException(
                        "미지원 파라미터 위치: in=" + param.in() + " (name=" + param.name() + ")");
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
