package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApiSpec(
        String apiId,
        String apiName,
        String description,
        String baseUrl,
        String endpoint,
        String httpMethod,
        String responseFormat,
        List<RequestParameter> requestParameters,
        List<ResponseField> responseFields,
        ErrorSpec errorSpec) {

    public ApiSpec {
        requestParameters = requestParameters == null ? List.of() : requestParameters;
        responseFields = responseFields == null ? List.of() : responseFields;
    }
}
