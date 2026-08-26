package kr.go.h2spec.client.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

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

    private ClientHttpResponse intercept(String body) throws IOException {
        return intercept(URI.create("http://api.example/test"), body);
    }

    private ClientHttpResponse intercept(URI uri, String body) throws IOException {
        MockClientHttpRequest request = new MockClientHttpRequest(HttpMethod.GET, uri);
        MockClientHttpResponse response =
                new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
        return interceptor.intercept(request, new byte[0], (req, requestBody) -> response);
    }
}
