package kr.go.h2spec.eval;

import kr.go.h2spec.eval.GoldFile.GoldField;
import kr.go.h2spec.eval.GoldFile.GoldOperation;
import kr.go.h2spec.eval.GoldFile.GoldParameter;

import java.util.List;

/**
 * 테스트용 정답 오퍼레이션. 기본은 지금 corpus 정답처럼 url·request·response만 있고 0-4의 새 키는 없다.
 */
final class GoldBuilder {

    private String name;
    private String title;
    private String url;
    private List<String> request;
    private List<String> response;
    private String responseFormat;
    private String successCode;
    private List<String> successCodeCandidates;
    private String auth;
    private List<GoldParameter> requestDetail;
    private List<GoldField> responseDetail;
    private Boolean supportedByGenerator;

    GoldBuilder(String url, List<String> request, List<String> response) {
        this.url = url;
        this.request = request;
        this.response = response;
    }

    /** IrBuilder.gateway와 같은 오퍼레이션의 정답 */
    static GoldBuilder gateway(String operation) {
        return new GoldBuilder("http://apis.data.go.kr/1234567/TestService/" + operation, List.of("serviceKey"),
                List.of("stationName"));
    }

    GoldBuilder name(String value) {
        name = value;
        return this;
    }

    GoldBuilder title(String value) {
        title = value;
        return this;
    }

    GoldBuilder responseFormat(String value) {
        responseFormat = value;
        return this;
    }

    GoldBuilder successCode(String value, String... candidates) {
        successCode = value;
        successCodeCandidates = candidates.length == 0 ? null : List.of(candidates);
        return this;
    }

    GoldBuilder auth(String value) {
        auth = value;
        return this;
    }

    GoldBuilder requestDetail(GoldParameter... parameters) {
        requestDetail = List.of(parameters);
        return this;
    }

    GoldBuilder responseDetail(GoldField... fields) {
        responseDetail = List.of(fields);
        return this;
    }

    GoldBuilder supportedByGenerator(boolean value) {
        supportedByGenerator = value;
        return this;
    }

    GoldOperation build() {
        return new GoldOperation(name, title, url, request, response, null, responseFormat, successCode,
                successCodeCandidates, auth, requestDetail, responseDetail, supportedByGenerator);
    }
}
