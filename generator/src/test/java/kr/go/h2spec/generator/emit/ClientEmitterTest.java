package kr.go.h2spec.generator.emit;

import kr.go.h2spec.generator.ir.IrLoader;
import kr.go.h2spec.generator.ir.IrSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

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
