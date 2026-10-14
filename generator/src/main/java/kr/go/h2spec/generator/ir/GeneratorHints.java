package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 생성에 쓰는 힌트.
 *
 * @param responseFormatParameter 응답 형식을 고르는 요청 파라미터와 넣을 값. generator는 아는 이름(returnType·dataType·resultType)이면
 *                                힌트 없이도 값을 채우므로, llm 엔진은 그 밖의 이름(type, apiType 등)일 때만 적는다. 없으면 null
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GeneratorHints(String targetPackage, ResponseFormatParameter responseFormatParameter) {

    public GeneratorHints(String targetPackage) {
        this(targetPackage, null);
    }

    /**
     * 응답 형식 파라미터와 거기 넣을 값.
     *
     * @param name  요청 파라미터 이름. requestParameters에 있어야 쓴다
     * @param value 넣을 값. 비면 responseFormat을 소문자로 넣는다
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResponseFormatParameter(String name, String value) {
    }
}
