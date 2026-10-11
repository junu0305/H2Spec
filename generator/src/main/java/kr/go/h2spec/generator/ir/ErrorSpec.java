package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ErrorSpec(String successResultCode, List<String> resultCodeFieldCandidates) {

    // 후보가 없는 IR(손으로 쓴 IR 등)은 빈 목록으로 받는다. 생성 클라이언트는 인터셉터의 기본 이름으로만 결과코드를 찾는다
    public ErrorSpec {
        resultCodeFieldCandidates = resultCodeFieldCandidates == null ? List.of() : resultCodeFieldCandidates;
    }

    public ErrorSpec(String successResultCode) {
        this(successResultCode, List.of());
    }
}
