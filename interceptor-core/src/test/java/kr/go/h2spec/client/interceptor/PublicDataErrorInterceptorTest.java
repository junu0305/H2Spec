package kr.go.h2spec.client.interceptor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PublicDataErrorInterceptorTest {

    private final PublicDataErrorInterceptor interceptor = new PublicDataErrorInterceptor();

    @Test
    void 정상_XML_응답은_그대로_통과한다() {
        assertDoesNotThrow(() -> intercept("""
                <response><header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>\
                <body><items><item><stationName>종로구</stationName></item></items></body></response>"""));
    }

    @Test
    void RestTemplate_전체_스택에서_인터셉터가_읽은_바디를_컨버터가_다시_읽는다() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new PublicDataErrorInterceptor());
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate)
                .bufferContent()   // BufferingClientHttpRequestFactory와 같은 효과
                .build();
        server.expect(requestTo("http://api.example/ok")).andRespond(withSuccess(
                "<response><header><resultCode>00</resultCode></header><body>DATA</body></response>",
                MediaType.TEXT_XML));

        String body = restTemplate.getForObject("http://api.example/ok", String.class);

        assertTrue(body.contains("DATA"));
    }

    @Test
    void XML_resultCode가_실패면_예외를_던진다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept("""
                <response><header><resultCode>22</resultCode>\
                <resultMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</resultMsg></header></response>"""));

        assertEquals("22", e.getResultCode());
        assertTrue(e.getResultMsg().contains("LIMITED_NUMBER"));
    }

    @Test
    void 공공데이터_인증실패_XML을_감지한다() {
        // 실제 공공데이터포털 인증 실패 응답 형태 (cmmMsgHeader/returnReasonCode)
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept("""
                <OpenAPI_ServiceResponse><cmmMsgHeader>\
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>\
                <returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"""));

        assertEquals("30", e.getResultCode());
    }

    @Test
    void 실제_인증실패_응답에_사전의_조치_안내가_붙는다() {
        // 잘못된 인증키로 실제 호출해 받은 응답 원문 (HTTP 403, returnReasonCode 30)
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept("""
                <?xml version="1.0" encoding="UTF-8"?>
                <OpenAPI_ServiceResponse>
                <cmmMsgHeader>
                  <errMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</errMsg>
                  <returnAuthMsg>등록되지 않은 서비스키</returnAuthMsg>
                  <returnReasonCode>30</returnReasonCode>
                </cmmMsgHeader>
                </OpenAPI_ServiceResponse>"""));

        assertEquals("30", e.getResultCode());
        assertTrue(e.getMessage().contains("조치: 인증키를 확인한다"), e.getMessage());
    }

    @Test
    void 결과코드가_없어도_에러명으로_조치_안내를_찾는다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> intercept("LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR"));

        assertNull(e.getResultCode());
        assertTrue(e.getMessage().contains("일일 트래픽을 넘겼다"), e.getMessage());
    }

    @Test
    void 정상_JSON_응답은_그대로_통과한다() throws IOException {
        assertDoesNotThrow(() -> intercept("""
                {"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"totalCount":1}}}"""));
    }

    @Test
    void JSON_resultCode가_실패면_예외를_던진다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept("""
                {"resultCode":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}"""));

        assertEquals("30", e.getResultCode());
    }

    @Test
    void 데이터_안의_errorCode_필드를_결과코드로_오인하지_않는다() {
        // 응답 데이터에 errorCode라는 이름의 필드가 있어도(예: 장비 에러코드),
        // 진짜 결과코드(header.resultCode)가 정상이면 성공으로 처리해야 한다
        assertDoesNotThrow(() -> intercept("""
                {"body":{"items":[{"errorCode":"E01","desc":"장비 자체 에러코드"}]},\
                "header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."}}"""));
    }

    @Test
    void resultCode_없이_에러_키워드만_있으면_폴백으로_감지한다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> intercept("SERVICE ERROR - HTTP ROUTING ERROR"));

        assertNull(e.getResultCode());
        assertTrue(e.getRawBodySnippet().contains("SERVICE ERROR"));
    }

    @Test
    void HTML_에러_페이지도_키워드_폴백으로_감지한다() {
        assertThrows(PublicDataApiException.class, () -> intercept(
                "<html><body><h1>SERVICE ERROR</h1></body></html>"));
    }

    @Test
    void 빈_바디는_그대로_통과한다() {
        assertDoesNotThrow(() -> intercept(""));
    }

    @Test
    void 키워드_없는_일반_텍스트는_통과한다() {
        assertDoesNotThrow(() -> intercept("plain text response"));
    }

    @Test
    void DOCTYPE이_있는_XML은_외부_엔티티를_해석하지_않고_안전하게_처리한다() {
        // XXE 방어: DOCTYPE 선언이 있으면 파싱을 거부하고 키워드 폴백으로 넘어간다
        assertDoesNotThrow(() -> intercept("""
                <?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>\
                <response><data>&xxe;</data></response>"""));
    }

    @Test
    void XML로_읽지_못하는_응답에도_표준_오류에_파서_메시지를_남기지_않는다() throws Throwable {
        // 대전 빅데이터포털이 준 HTML 페이지: DOCTYPE과 닫히지 않은 태그가 있다. JDK XML 파서는 오류 처리기가 없으면
        // 응답마다 "[Fatal Error] …"를 사용자 애플리케이션의 표준 오류에 찍는다
        String stderr = stderrOf(() -> assertDoesNotThrow(() -> intercept(
                "<!DOCTYPE html><html><head><title>목록</title></head><body><p>안내<br></body></html>")));

        assertEquals("", stderr);
    }

    @Test
    void 실패_예외에_요청_URI와_바디_스니펫이_담긴다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept("""
                <response><header><resultCode>99</resultCode><resultMsg>UNKNOWN</resultMsg></header></response>"""));

        assertEquals("http://api.example/test", e.getRequestUri());
        assertEquals(200, e.getHttpStatus());
        assertTrue(e.getRawBodySnippet().contains("resultCode"));
    }

    @Test
    void 예외_메시지의_URI에서_서비스키를_마스킹한다() {
        URI uri = URI.create("http://api.example/test?serviceKey=SECRET123&pageNo=1");

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(uri, """
                <response><header><resultCode>30</resultCode></header></response>"""));

        assertFalse(e.getMessage().contains("SECRET123"), "예외 메시지에 서비스키가 노출됨");
        assertFalse(e.getRequestUri().contains("SECRET123"), "requestUri에 서비스키가 노출됨");
        assertTrue(e.getRequestUri().contains("serviceKey=****"));
        assertTrue(e.getRequestUri().contains("pageNo=1"), "다른 파라미터는 보존돼야 함");
    }

    @Test
    void 에러_바디가_요청_URL을_에코해도_모든_예외_필드에서_서비스키를_마스킹한다() {
        // 일부 게이트웨이 오류 응답은 요청 URL(서비스키 포함)을 바디에 그대로 에코한다
        URI uri = URI.create("http://api.example/test?serviceKey=SECRET123");

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(uri, """
                <response><header><resultCode>30</resultCode>\
                <resultMsg>인증 실패: http://api.example/test?serviceKey=SECRET123</resultMsg>\
                </header></response>"""));

        assertFalse(e.getResultMsg().contains("SECRET123"), "resultMsg에 서비스키가 노출됨");
        assertFalse(e.getRawBodySnippet().contains("SECRET123"), "rawBodySnippet에 서비스키가 노출됨");
        assertFalse(e.getMessage().contains("SECRET123"), "예외 메시지에 서비스키가 노출됨");
        assertTrue(e.getResultMsg().contains("serviceKey=****"));
    }

    @Test
    void 대문자_ServiceKey_파라미터도_마스킹한다() {
        URI uri = URI.create("http://api.example/test?ServiceKey=SECRET123");

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(uri, """
                <response><header><resultCode>30</resultCode></header></response>"""));

        assertFalse(e.getRequestUri().contains("SECRET123"));
    }

    @Test
    void 키를_주면_경로와_다른_이름의_질의에_든_키_값을_모든_예외_필드에서_가린다() {
        // serviceKey가 아닌 자리(주소 경로, apiKey 질의)는 이름으로 찾을 수 없다. 오류 바디가 요청 주소를 에코하는 기관도 있다
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of(), "SECRET/KEY=");
        URI uri = URI.create("http://api.example/svc/SECRET%2FKEY%3D?apiKey=SECRET%2FKEY%3D&pageNo=1");

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(withKey, uri, """
                <response><header><resultCode>30</resultCode>\
                <resultMsg>등록되지 않은 키: SECRET/KEY=</resultMsg></header></response>"""));

        assertEquals("http://api.example/svc/****?apiKey=****&pageNo=1", e.getRequestUri());
        for (String text : List.of(e.getMessage(), e.getResultMsg(), e.getRawBodySnippet())) {
            assertFalse(text.contains("SECRET"), text);
        }
        assertEquals("30", e.getResultCode());
    }

    @Test
    void 키를_가린_뒤에_바디를_잘라_잘린_키가_남지_않는다() {
        // 자른 뒤에 가리면 500자 경계에 걸린 키의 앞부분이 남는다
        String key = "SECRETKEY1234567890";
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of(), key);
        String head = "<response><header><resultCode>30</resultCode></header><body>";
        // 키가 495번째 글자에서 시작해 500자 경계에 걸친다
        String body = head + "x".repeat(495 - head.length()) + key + "</body></response>";

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(withKey, body));

        assertFalse(e.getRawBodySnippet().contains("SECR"), e.getRawBodySnippet());
    }

    @Test
    void 숫자로만_된_결과코드는_앞자리_0을_떼고_성공코드와_비교한다() {
        // 기상청 중기예보는 표에 00, 응답 예제에 0을 적는다. 0, 00, 000은 같은 수다
        assertDoesNotThrow(() -> intercept(xmlWithResultCode("0")));
        assertDoesNotThrow(() -> intercept(xmlWithResultCode("000")));
        assertDoesNotThrow(() -> intercept(new PublicDataErrorInterceptor("0"), xmlWithResultCode("00")));
    }

    @Test
    void JSON_숫자_0으로_온_결과코드도_성공코드_00과_같게_본다() {
        assertDoesNotThrow(() -> intercept("""
                {"response":{"header":{"resultCode":0,"resultMsg":"NORMAL SERVICE."},"body":{"totalCount":1}}}"""));
    }

    @Test
    void 앞자리_0만_다른_오류_코드는_실패이고_받은_코드를_그대로_남긴다() {
        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> intercept(xmlWithResultCode("030")));

        assertEquals("030", e.getResultCode());
    }

    @Test
    void 숫자가_아닌_결과코드는_글자까지_같아야_성공이다() {
        // 선관위 개표소 문서는 성공코드가 INFO-00이다. 숫자가 아니므로 앞자리 0을 떼지 않는다
        PublicDataErrorInterceptor info = new PublicDataErrorInterceptor("INFO-00");

        assertDoesNotThrow(() -> intercept(info, xmlWithResultCode("INFO-00")));
        assertThrows(PublicDataApiException.class, () -> intercept(info, xmlWithResultCode("INFO-0")));
        assertThrows(PublicDataApiException.class, () -> intercept(info, xmlWithResultCode("info-00")));
        assertThrows(PublicDataApiException.class, () -> intercept(info, xmlWithResultCode("00")));
        assertThrows(PublicDataApiException.class, () -> intercept(xmlWithResultCode("INFO-00")));
    }

    @Test
    void 결과코드_이름을_주면_XML에서_그_이름으로_찾는다() {
        String failure = "<response><header><resultCd>99</resultCd></header><body/></response>";
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of("resultCd"));

        // 기본 이름만 찾으면 결과코드도 에러 키워드도 없어 오류 응답이 정상으로 넘어간다
        assertDoesNotThrow(() -> intercept(failure));
        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(withKey, failure));
        assertEquals("99", e.getResultCode());
        assertDoesNotThrow(() -> intercept(withKey, "<response><header><resultCd>00</resultCd></header></response>"));
    }

    @Test
    void 결과코드_이름을_주면_JSON에서_그_이름으로_찾는다() {
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of("resultCd"));

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(withKey, """
                {"result":{"resultCd":"30","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"}}"""));

        assertEquals("30", e.getResultCode());
        assertDoesNotThrow(() -> intercept(withKey, """
                {"result":{"resultCd":0,"resultMsg":"OK"},"list":[{"stationName":"종로구"}]}"""));
    }

    @Test
    void 준_결과코드_이름을_기본_이름보다_먼저_찾는다() {
        // 데이터에 기본 이름인 errorCode가 있어도 문서에 적힌 결과코드가 정상이면 정상이다
        String body = """
                {"result":{"resultCd":"00"},"items":{"item":{"errorCode":"E01"}}}""";

        assertThrows(PublicDataApiException.class, () -> intercept(body));
        assertDoesNotThrow(() -> intercept(new PublicDataErrorInterceptor("00", List.of("resultCd")), body));
    }

    @Test
    void 결과코드_이름을_주어도_기본_이름으로_계속_찾는다() {
        // 게이트웨이의 인증 실패 응답은 기관 문서와 상관없이 cmmMsgHeader/returnReasonCode로 온다
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of("resultCd"));

        PublicDataApiException e = assertThrows(PublicDataApiException.class, () -> intercept(withKey, """
                <OpenAPI_ServiceResponse><cmmMsgHeader>\
                <returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>\
                <returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"""));

        assertEquals("30", e.getResultCode());
    }

    @Test
    void 준_결과코드_이름은_XML에서도_응답_메타데이터_깊이까지만_찾는다() {
        // code처럼 흔한 이름이면 header에 없을 때 데이터 항목 안의 같은 이름이 결과코드로 잡혀 정상 응답이 예외가 된다
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of("code"));
        String itemCode = "<response><header><resultCode>00</resultCode></header><body><items><item><code>A12</code></item>"
                + "</items></body></response>";

        assertDoesNotThrow(() -> intercept(withKey, itemCode));
        PublicDataApiException e = assertThrows(PublicDataApiException.class,
                () -> intercept(withKey, "<response><header><code>99</code></header><body/></response>"));
        assertEquals("99", e.getResultCode());
    }

    @Test
    void JSON_결과코드가_null이면_결과코드가_없는_것으로_본다() {
        // "null"이라는 결과코드로 읽으면 성공코드와 달라 정상 응답이 예외가 된다
        PublicDataErrorInterceptor withKey = new PublicDataErrorInterceptor("00", List.of("resultCd"));

        assertDoesNotThrow(() -> intercept(withKey, """
                {"result":{"resultCd":null,"resultCode":"00"},"list":[]}"""));
        assertDoesNotThrow(() -> intercept("""
                {"response":{"header":{"resultCode":null,"resultMsg":"OK"}}}"""));
    }

    private static String xmlWithResultCode(String resultCode) {
        return "<response><header><resultCode>" + resultCode + "</resultCode></header><body/></response>";
    }

    private ClientHttpResponse intercept(String body) throws IOException {
        return intercept(interceptor, body);
    }

    private ClientHttpResponse intercept(URI uri, String body) throws IOException {
        return intercept(interceptor, uri, body);
    }

    private ClientHttpResponse intercept(PublicDataErrorInterceptor target, String body) throws IOException {
        return intercept(target, URI.create("http://api.example/test"), body);
    }

    private ClientHttpResponse intercept(PublicDataErrorInterceptor target, URI uri, String body) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        MockClientHttpResponse response =
                new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        return target.intercept(request, new byte[0], (req, requestBody) -> response);
    }

    private static String stderrOf(Executable action) throws Throwable {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            action.execute();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
