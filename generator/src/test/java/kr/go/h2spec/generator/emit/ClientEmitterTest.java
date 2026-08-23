package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClientEmitterTest {

    @Test
    void 골든파일과_일치한다() throws Exception {
        IrSpec ir = new IrLoader().load(resource("/ir/schema-example.json"));

        String actual = new ClientEmitter().emit(ir);

        String expected = Files.readString(resource("/golden/RTMSDataSvcAptTradeDevClient.java.txt"));
        assertEquals(expected, actual);
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

    private Path resource(String name) throws Exception {
        return Path.of(getClass().getResource(name).toURI());
    }
}
