package kr.go.h2spec.eval;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 엔진이 문서에서 찾은 오퍼레이션 하나. {@code SpecParseResult}의 오퍼레이션 기록과 같은 내용을 담는다.
 * 지표는 이 기록만 보므로 rule·llm 엔진이 같은 지표 코드를 쓴다.
 *
 * @param operationName 영문 오퍼레이션명. 없으면 null
 * @param title         국문 기능명. 없으면 null
 * @param ir            IR. 추출하지 못했으면 null. "지원 범위 밖"으로 건너뛴 오퍼레이션도 정규화한 IR을 담는다
 * @param skipReason    건너뛴 이유. 건너뛰지 않았으면 null
 * @param outOfScope    "지원 범위 밖"으로 건너뛰었는지. rule 엔진은 범위를 판정하지 않아 늘 false다
 */
public record EngineOperation(String operationName, String title, ObjectNode ir, String skipReason,
                              boolean outOfScope) {

    /**
     * 범위 판정 전의 추출 결과가 있는지 본다. 재현율·정확도는 이 결과로 잰다.
     *
     * @return IR이 있으면 true
     */
    public boolean extracted() {
        return ir != null;
    }

    /**
     * IR의 오퍼레이션 주소.
     *
     * @return baseUrl + endpoint. IR이 없으면 null
     */
    public String url() {
        return ir == null ? null : ir.path("api").path("baseUrl").asText() + ir.path("api").path("endpoint").asText();
    }

    /**
     * IR의 apiId.
     *
     * @return apiId. IR이 없으면 null
     */
    public String apiId() {
        return ir == null ? null : ir.path("api").path("apiId").asText();
    }
}
