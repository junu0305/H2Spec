package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static kr.go.h2spec.generator.GoldenFileAssertions.assertEqualsIgnoringLineEndings;

class ClientEmitterTest {

    @Test
    void 골든파일과_일치한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));

        String actual = new ClientEmitter().emit(ir);

        String expected = Files.readString(resource("/golden/RTMSDataSvcAptTradeDevClient.java.txt"));
        assertEqualsIgnoringLineEndings(expected, actual);
    }

    @Test
    void responseFormat이_JSON이면_ObjectMapper를_쓴다() throws Exception {
        IrSpec xml = new IrLoader().load(resource("/ir/schema-example.json"));
        IrSpec json = new IrSpec(
                new kr.go.h2spec.generator.ir.ApiSpec(
                        xml.api().apiId(), xml.api().apiName(), xml.api().description(),
                        xml.api().baseUrl(), xml.api().endpoint(), xml.api().httpMethod(),
                        "JSON", xml.api().requestParameters(), xml.api().responseFields(),
                        xml.api().errorSpec()),
                xml.generatorHints());

        String source = new ClientEmitter().emit(json);

        assertTrue(source.contains("import com.fasterxml.jackson.databind.ObjectMapper;"));
        assertFalse(source.contains("XmlMapper"));
    }

    @Test
    void integer_파라미터는_Long으로_방출한다() throws Exception {
        // 발표시각(201310170600)처럼 int 범위를 넘는 값이 파라미터로 오면 Integer로는 담기지 않는다
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));

        String source = new ClientEmitter().emit(ir);

        assertTrue(source.contains("Long numOfRows"));
        assertFalse(source.contains("Integer numOfRows"));
    }

    @Test
    void 포맷_파라미터는_메서드에서_감추고_URL에_고정값으로_박는다() throws Exception {
        // 사용자가 returnType에 "json"을 넘기면 서버는 JSON을 주는데 매퍼는 고정이라 파싱이 깨진다.
        // 생성 시점에 포맷을 확정하고 손잡이를 없앤다.
        String source = new ClientEmitter().emit(withFormatParam("JSON"));

        assertFalse(source.contains("String returnType"), "메서드 시그니처에 포맷 파라미터가 남으면 안 된다");
        assertTrue(source.contains("url.append(\"&returnType=json\");"));
        assertTrue(source.contains("objectMapper.readValue"));
    }

    @Test
    void XML이면_포맷_파라미터에_xml을_박는다() throws Exception {
        String source = new ClientEmitter().emit(withFormatParam("XML"));

        assertFalse(source.contains("String returnType"));
        assertTrue(source.contains("url.append(\"&returnType=xml\");"));
        assertTrue(source.contains("xmlMapper.readValue"));
    }

    @Test
    void JSON이면_루트_언래핑을_켠_ObjectMapper를_만든다() throws Exception {
        // DTO의 @JsonRootName과 짝을 이룬다. 둘 중 하나만 있으면 역직렬화가 조용히 빈 객체를 낸다
        String source = new ClientEmitter().emit(withFormatParam("JSON"));

        assertTrue(source.contains("DeserializationFeature.UNWRAP_ROOT_VALUE"));
        assertTrue(source.contains("import com.fasterxml.jackson.databind.DeserializationFeature;"));
    }

    @Test
    void 인증키_파라미터_이름의_대소문자가_달라도_인식한다() throws Exception {
        // 천문연구원 특일정보 문서는 serviceKey가 아니라 ServiceKey로 적혀 있다
        IrSpec ir = withServiceKeyNamed("ServiceKey");

        String source = new ClientEmitter().emit(ir);

        assertFalse(source.contains("@param ServiceKey"), "인증키는 생성자로 받으므로 API 메서드 인자에 남으면 안 된다");
        assertTrue(source.contains("url.append(\"?ServiceKey=\")"), "문서에 적힌 이름 그대로 보내야 한다");
    }

    @Test
    void 여러_세그먼트_엔드포인트도_유효한_메서드명을_만든다() throws Exception {
        // "/items/{itemId}/detail" 을 그대로 쓰면 메서드명에 슬래시와 중괄호가 들어가 컴파일되지 않는다
        IrSpec ir = withEndpoint("/items/{itemId}/detail");

        String source = new ClientEmitter().emit(ir);

        assertFalse(source.contains("items/{itemId}/detail("), "메서드명에 경로 구분자가 남으면 안 된다: " + source);
        assertTrue(source.contains("public RTMSDataSvcAptTradeDevResponse itemsItemidDetail("),
                "경로 세그먼트를 낱말로 이어붙인 이름이어야 한다");
    }

    private IrSpec withEndpoint(String endpoint) throws Exception {
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        kr.go.h2spec.generator.ir.ApiSpec a = base.api();
        return new IrSpec(
                new kr.go.h2spec.generator.ir.ApiSpec(a.apiId(), a.apiName(), a.description(), a.baseUrl(),
                        endpoint, a.httpMethod(), a.responseFormat(), a.requestParameters(),
                        a.responseFields(), a.errorSpec()),
                base.generatorHints());
    }

    /** schema-example 기반 IR에서 serviceKey 파라미터 이름만 바꾼 IR */
    @Test
    void path_파라미터는_경로용_인코더로_치환한다() throws Exception {
        // URLEncoder는 공백을 +로 바꾸는데 경로 세그먼트에서 +는 공백이 아니라 글자 그대로다
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        RequestParameter path = new RequestParameter(
                "LAWD_CD", "path", "string", true, "법정동 코드", null, null, null);
        ApiSpec api = new ApiSpec(
                base.api().apiId(), base.api().apiName(), base.api().description(), base.api().baseUrl(),
                "/v1/apt/{LAWD_CD}", base.api().httpMethod(), base.api().responseFormat(),
                List.of(base.api().requestParameters().get(0), path), base.api().responseFields(),
                base.api().errorSpec());

        String source = new ClientEmitter().emit(new IrSpec(api, base.generatorHints()));

        assertTrue(source.contains("private static String encodePath(String value)"), source);
        assertTrue(source.contains("encode(value).replace(\"+\", \"%20\")"),
                "공백이 %20으로 나가야 한다");
    }

    @Test
    void path_파라미터가_없으면_경로용_인코더를_만들지_않는다() throws Exception {
        String source = new ClientEmitter().emit(new IrLoader().load(resource("/ir/schema-example.json")));

        assertFalse(source.contains("encodePath"), "쓰이지 않는 헬퍼를 넣지 않는다");
    }

    private IrSpec withServiceKeyNamed(String name) throws Exception {
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        List<kr.go.h2spec.generator.ir.RequestParameter> params = new java.util.ArrayList<>();
        for (kr.go.h2spec.generator.ir.RequestParameter p : base.api().requestParameters()) {
            params.add("serviceKey".equals(p.name())
                    ? new kr.go.h2spec.generator.ir.RequestParameter(name, p.in(), p.type(),
                            p.required(), p.description(), p.defaultValue(), p.example(), p.pattern())
                    : p);
        }
        kr.go.h2spec.generator.ir.ApiSpec a = base.api();
        return new IrSpec(
                new kr.go.h2spec.generator.ir.ApiSpec(a.apiId(), a.apiName(), a.description(), a.baseUrl(),
                        a.endpoint(), a.httpMethod(), a.responseFormat(), params, a.responseFields(), a.errorSpec()),
                base.generatorHints());
    }

    /** schema-example 기반 IR에 returnType 파라미터를 붙이고 responseFormat을 바꾼 IR */
    private IrSpec withFormatParam(String format) throws Exception {
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        List<kr.go.h2spec.generator.ir.RequestParameter> params =
                new java.util.ArrayList<>(base.api().requestParameters());
        params.add(new kr.go.h2spec.generator.ir.RequestParameter(
                "returnType", "query", "string", false, "데이터 표출방식 xml 또는 json", null, "xml", null));
        kr.go.h2spec.generator.ir.ApiSpec a = base.api();
        return new IrSpec(
                new kr.go.h2spec.generator.ir.ApiSpec(
                        a.apiId(), a.apiName(), a.description(), a.baseUrl(), a.endpoint(),
                        a.httpMethod(), format, params, a.responseFields(), a.errorSpec()),
                base.generatorHints());
    }

    @Test
    void 응답을_바이트배열로_받아_인코딩_손상을_피한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));

        String source = new ClientEmitter().emit(ir);

        assertTrue(source.contains("byte[] body = restTemplate.getForObject(uri, byte[].class);"));
        assertFalse(source.contains("String body = restTemplate.getForObject(uri, String.class);"));
    }

    @Test
    void path_파라미터는_endpoint에_치환하고_query로_붙이지_않는다() throws Exception {
        IrSpec base = new IrLoader().load(resource("/ir/schema-example.json"));
        RequestParameter path = new RequestParameter(
                "LAWD_CD", "path", "string", true, "법정동 코드", null, null, null);
        ApiSpec api = new ApiSpec(
                base.api().apiId(), base.api().apiName(), base.api().description(), base.api().baseUrl(),
                "/v1/apt/{LAWD_CD}", base.api().httpMethod(), base.api().responseFormat(),
                List.of(base.api().requestParameters().get(0), path), base.api().responseFields(),
                base.api().errorSpec());

        String source = new ClientEmitter().emit(new IrSpec(api, base.generatorHints()));

        assertTrue(source.contains("replace(\"{LAWD_CD}\", encodePath(String.valueOf(lawdCd)))"));
        assertFalse(source.contains("url.append(\"&LAWD_CD=\")"));
    }

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }

    private String normalizeLineEndings(String value) {
        return value.replace("\r\n", "\n");
    }
}
