package kr.go.h2spec.generator;

import kr.go.h2spec.client.interceptor.PublicDataApiException;
import kr.go.h2spec.generator.ir.ApiSpec;
import kr.go.h2spec.generator.ir.ErrorSpec;
import kr.go.h2spec.generator.ir.GeneratorHints;
import kr.go.h2spec.generator.ir.IrSpec;
import kr.go.h2spec.generator.ir.RequestParameter;
import kr.go.h2spec.generator.ir.ResponseField;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static kr.go.h2spec.generator.GeneratedSourceCompiler.assertCompiles;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IR의 결과코드 위치 후보를 생성 클라이언트가 인터셉터에 넘기는지 본다(설계 3-3).
 * 생성 코드를 컴파일하고, 생성 클라이언트가 만드는 RestTemplate의 인터셉터가 그 이름으로 결과코드를 찾는지까지 본다.
 */
class ResultCodeCandidatesTest {

    private static final String CLIENT_CLASS = "kr.go.h2spec.client.sampleapi.SampleApiClient";

    @TempDir
    Path tempDir;

    @Test
    void 기본_이름에_없는_결과코드_이름은_생성_클라이언트가_인터셉터에_넘긴다() throws Exception {
        // llm 정규화는 문서에 적힌 결과코드 필드를 기본 후보 목록 앞에 더한다(설계 5.2)
        List<String> candidates = List.of("response.header.resultCd",
                "response.header.resultCode", "resultCode", "RESULT_CODE", "cmmMsgHeader.returnReasonCode");

        List<Path> written = new CodeGenerator().generate(sampleIr(candidates), tempDir.resolve("out"));
        assertCompiles(written, classes());

        String client = Files.readString(written.get(1));
        assertTrue(client.contains("import java.util.List;"), client);
        assertTrue(client.contains("new PublicDataErrorInterceptor(SUCCESS_CODE, List.of(\"resultCd\"))"), client);
        ClientHttpRequestInterceptor interceptor = generatedClientInterceptor();
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> InterceptorProbe.respond(
                interceptor, "<response><header><resultCd>30</resultCd></header><body/></response>"));
        assertEquals("30", e.getResultCode());
        assertDoesNotThrow(() -> InterceptorProbe.respond(
                interceptor, "<response><header><resultCd>00</resultCd></header><body/></response>"));
    }

    /** 생성 클라이언트가 기본 생성자에서 쓰는 RestTemplate을 만들어 붙은 인터셉터를 꺼낸다 */
    private ClientHttpRequestInterceptor generatedClientInterceptor() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classes().toUri().toURL()}, getClass().getClassLoader())) {
            Method factory = loader.loadClass(CLIENT_CLASS).getDeclaredMethod("defaultRestTemplate");
            factory.setAccessible(true);
            RestTemplate restTemplate = (RestTemplate) factory.invoke(null);
            assertEquals(1, restTemplate.getInterceptors().size());
            return restTemplate.getInterceptors().get(0);
        }
    }

    private Path classes() {
        return tempDir.resolve("classes");
    }

    private static IrSpec sampleIr(List<String> resultCodeCandidates) {
        return new IrSpec(
                new ApiSpec("SampleApi", "샘플 조회", "샘플 자료를 조회한다",
                        "http://apis.data.go.kr/1234567/SampleService", "/getSample", "GET", "XML",
                        List.of(new RequestParameter("serviceKey", "query", "string", true, "인증키", null, null, null)),
                        List.of(new ResponseField("response.header.resultCd", "string", "결과코드", true),
                                new ResponseField("response.body.items.item[].stationName", "string", "측정소명", null)),
                        new ErrorSpec("00", resultCodeCandidates)),
                new GeneratorHints("kr.go.h2spec.client.sampleapi"));
    }
}
