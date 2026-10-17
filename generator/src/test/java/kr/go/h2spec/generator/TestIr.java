package kr.go.h2spec.generator;

import kr.go.h2spec.generator.ir.ApiAuth;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.ir.ResponseField;

import java.util.ArrayList;
import java.util.List;

/** generator 검사를 모두 통과하는 IR. 테스트마다 바꿀 곳만 바꾼다. 클라이언트 클래스는 {@link #CLIENT_CLASS}다 */
final class TestIr {

    static final String CLIENT_CLASS = "kr.go.h2spec.client.sampleapi.SampleApiClient";

    String baseUrl = "http://apis.data.go.kr/1234567/SampleService";
    String endpoint = "/getSample";
    String responseFormat = "XML";
    List<RequestParameter> params = new ArrayList<>(List.of(
            query("serviceKey", true),
            query("pageNo", false)));
    List<ResponseField> fields = new ArrayList<>(List.of(
            field("response.header.resultCode", "string"),
            field("response.body.items.item[].stationName", "string")));
    ApiAuth auth;
    boolean rootless;

    IrSpec build() {
        return new IrSpec(
                new ApiSpec("SampleApi", "샘플 조회", "샘플 자료를 조회한다", baseUrl, endpoint, "GET", responseFormat,
                        params, fields, new ErrorSpec("00"), auth, rootless),
                new GeneratorHints("kr.go.h2spec.client.sampleapi"));
    }

    static RequestParameter query(String name, boolean required) {
        return new RequestParameter(name, "query", "string", required, name + " 설명", null, null, null);
    }

    static RequestParameter path(String name) {
        return new RequestParameter(name, "path", "string", true, name + " 설명", null, null, null);
    }

    static ResponseField field(String path, String type) {
        return new ResponseField(path, type, path + " 설명", null);
    }
}
