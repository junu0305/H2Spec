package kr.go.h2spec.client.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicDataErrorCatalogTest {

    @Test
    void 표준_에러코드를_찾는다() {
        var entry = PublicDataErrorCatalog.find("30").orElseThrow();

        assertEquals("SERVICE_KEY_IS_NOT_REGISTERED_ERROR", entry.name());
        assertEquals("등록되지 않은 서비스키", entry.meaning());
        assertFalse(entry.action().isBlank());
    }

    @Test
    void 기관마다_다른_자릿수를_같은_코드로_본다() {
        // 어떤 문서는 1, 어떤 문서는 01로 적는다
        assertEquals(PublicDataErrorCatalog.find("01").orElseThrow().name(),
                PublicDataErrorCatalog.find("1").orElseThrow().name());
    }

    @Test
    void 코드가_없으면_결과메시지의_에러명으로_찾는다() {
        // 결과코드를 비워두고 resultMsg에만 에러명을 담는 기관이 있다
        var entry = PublicDataErrorCatalog.findByMessage(
                "SERVICE_KEY_IS_NOT_REGISTERED_ERROR").orElseThrow();

        assertEquals("30", entry.code());
    }

    @Test
    void 자릿수가_큰_숫자_코드에도_깨지지_않는다() {
        // 예외 메시지를 만들다 터지면 원래 오류가 가려진다
        assertTrue(PublicDataErrorCatalog.find("99999999999999999999").isEmpty());
        assertTrue(PublicDataErrorCatalog.find("0000000030").isPresent());
        assertEquals("APPLICATION_ERROR", PublicDataErrorCatalog.find("0001").orElseThrow().name());
    }

    @Test
    void 같은_코드의_다른_에러명_표기도_찾는다() {
        // 문서는 SERVICETIME_OUT, 실제 응답은 SERVICETIMEOUT_ERROR로 적는다
        assertEquals("05", PublicDataErrorCatalog.findByMessage("SERVICETIMEOUT_ERROR").orElseThrow().code());
        assertEquals("05", PublicDataErrorCatalog.findByMessage("SERVICETIME_OUT").orElseThrow().code());
    }

    @Test
    void 모르는_코드는_비어있다() {
        assertTrue(PublicDataErrorCatalog.find("7777").isEmpty());
        assertTrue(PublicDataErrorCatalog.find(null).isEmpty());
        assertTrue(PublicDataErrorCatalog.findByMessage("정상 응답입니다").isEmpty());
    }

    @Test
    void 사전_항목은_모두_필수값과_출처를_갖는다() {
        List<PublicDataErrorCatalog.ErrorCode> entries = PublicDataErrorCatalog.entries();

        assertFalse(entries.isEmpty());
        for (var entry : entries) {
            assertFalse(entry.code().isBlank(), entry.toString());
            assertFalse(entry.name().isBlank(), entry.toString());
            assertFalse(entry.meaning().isBlank(), entry.toString());
            assertFalse(entry.action().isBlank(), entry.toString());
            assertFalse(entry.source().isBlank(), entry.toString());
        }
    }

    @Test
    void 같은_코드가_중복_등록되지_않는다() {
        List<String> codes = PublicDataErrorCatalog.entries().stream()
                .map(PublicDataErrorCatalog.ErrorCode::code).toList();

        assertEquals(codes.size(), codes.stream().distinct().count(),
                "중복 코드: " + codes);
    }

    @Test
    void 사전에_추가한_에러명이_키워드_탐지에도_쓰인다() {
        // 사전 항목이 탐지 키워드와 따로 놀면 항목을 추가해도 탐지가 좋아지지 않는다
        var interceptor = new PublicDataErrorInterceptor();
        var request = new MockClientHttpRequest(HttpMethod.GET, URI.create("http://api/x"));
        var response = new MockClientHttpResponse(
                "UNREGISTERED_IP_ERROR".getBytes(StandardCharsets.UTF_8), HttpStatus.OK);

        var e = assertThrows(PublicDataApiException.class,
                () -> interceptor.intercept(request, new byte[0], (req, body) -> response));

        assertTrue(e.getMessage().contains("서버 IP를 등록한다"), e.getMessage());
    }

    @Test
    void 예외_메시지에_조치_안내가_붙는다() {
        var exception = new PublicDataApiException(
                "http://apis.data.go.kr/svc?serviceKey=abc", 200, "30",
                "SERVICE_KEY_IS_NOT_REGISTERED_ERROR", "<response/>");

        assertTrue(exception.getMessage().contains("등록되지 않은 서비스키"), exception.getMessage());
        assertTrue(exception.getMessage().contains("조치:"), exception.getMessage());
    }

    @Test
    void 응답이_이미_담은_뜻을_두_번_적지_않는다() {
        var exception = new PublicDataApiException(
                "http://api/x", 403, "30", "등록되지 않은 서비스키", "<response/>");

        String message = exception.getMessage();
        assertEquals(1, message.split("등록되지 않은 서비스키", -1).length - 1, message);
        assertTrue(message.contains("조치:"), message);
    }

    @Test
    void 사전에_없는_코드면_메시지가_그대로다() {
        var exception = new PublicDataApiException(
                "http://apis.data.go.kr/svc", 200, "7777", "MYSTERY", "<response/>");

        assertFalse(exception.getMessage().contains("조치:"), exception.getMessage());
    }
}
