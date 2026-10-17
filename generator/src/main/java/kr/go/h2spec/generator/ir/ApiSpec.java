package kr.go.h2spec.generator.ir;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * @param auth             인증키를 넣는 자리. 없으면 generator가 요청 파라미터의 이름으로 정한다({@link ApiAuth})
 * @param responseRootless JSON 응답이 루트 키 하나로 감싸이지 않고 맨 바깥에 필드가 바로 오는지({"header": …, "body": …}).
 *                         그러면 responseFields 경로의 첫 칸이 맨 바깥 키다. 없으면 false이고 첫 칸은 응답을 감싼 루트다
 */
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
        ErrorSpec errorSpec,
        ApiAuth auth,
        boolean responseRootless) {

    public ApiSpec {
        requestParameters = requestParameters == null ? List.of() : requestParameters;
        responseFields = responseFields == null ? List.of() : responseFields;
    }

    /** auth와 responseRootless가 없는 IR. 값 하나를 바꿔 다시 만들 때는 이 생성자 말고 with 메서드를 써야 두 값이 남는다 */
    public ApiSpec(String apiId, String apiName, String description, String baseUrl, String endpoint,
                   String httpMethod, String responseFormat, List<RequestParameter> requestParameters,
                   List<ResponseField> responseFields, ErrorSpec errorSpec) {
        this(apiId, apiName, description, baseUrl, endpoint, httpMethod, responseFormat, requestParameters,
                responseFields, errorSpec, null, false);
    }

    public ApiSpec withResponseFormat(String format) {
        return new ApiSpec(apiId, apiName, description, baseUrl, endpoint, httpMethod, format, requestParameters,
                responseFields, errorSpec, auth, responseRootless);
    }

    public ApiSpec withErrorSpec(ErrorSpec spec) {
        return new ApiSpec(apiId, apiName, description, baseUrl, endpoint, httpMethod, responseFormat, requestParameters,
                responseFields, spec, auth, responseRootless);
    }
}
