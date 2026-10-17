package kr.go.h2spec.parser.llm.gemini;

import com.fasterxml.jackson.databind.node.MissingNode;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiResponsesTest {

    @Test
    void 생각_조각을_빼고_글을_잇고_마지막_조각의_사용량을_써서_생각_토큰을_출력에_더한다() throws Exception {
        String stream = chunk("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"생각\",\"thought\":true},"
                + "{\"text\":\"{\\\"a\\\":\"}]}}],\"usageMetadata\":{\"promptTokenCount\":12000},\"modelVersion\":\"gemini-3.8-flash\"}")
                + chunk("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"1}\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":12000,\"cachedContentTokenCount\":8000,"
                + "\"candidatesTokenCount\":3000,\"thoughtsTokenCount\":500},\"modelVersion\":\"gemini-3.8-flash\"}");

        LlmResponse response = GeminiResponses.fromStream(new StringReader(stream));

        assertEquals("{\"a\":1}", response.json());
        assertEquals("gemini-3.8-flash", response.model());
        assertEquals(LlmResponse.Finish.COMPLETE, response.finish());
        assertEquals(new LlmResponse.Usage(4000, 8000, 0, 3500, 500), response.usage());
    }

    @Test
    void 출력_한도는_잘림이고_안전_차단과_프롬프트_차단은_거절이며_끝나는_이유_없이_끝나면_예외다() throws Exception {
        assertEquals(LlmResponse.Finish.TRUNCATED, GeminiResponses.fromStream(new StringReader(chunk(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\\\"a\\\"\"}]},\"finishReason\":\"MAX_TOKENS\"}]}")))
                .finish());
        assertEquals(LlmResponse.Finish.REFUSED, GeminiResponses.fromStream(new StringReader(chunk(
                "{\"candidates\":[{\"finishReason\":\"SAFETY\"}]}"))).finish());
        assertEquals(LlmResponse.Finish.REFUSED, GeminiResponses.fromStream(new StringReader(chunk(
                "{\"promptFeedback\":{\"blockReason\":\"PROHIBITED_CONTENT\"}}"))).finish());
        LlmException cut = assertThrows(LlmException.class, () -> GeminiResponses.fromStream(new StringReader(chunk(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{\"}]}}]}"))));
        assertEquals(LlmException.Kind.INTERRUPTED, cut.kind(), "서버가 스트림을 닫아 끊긴 것은 다시 불러도 같고 비용이 겹친다");
        assertEquals(LlmException.Kind.INTERRUPTED, assertThrows(LlmException.class,
                () -> GeminiResponses.fromStream(new StringReader(chunk("{not json")))).kind());
    }

    @Test
    void 잘림이나_거절이_아닌_끝난_이유는_출력과_토큰을_넘겨_JSON으로_판단하게_하고_계정_제한은_멈춘다() throws Exception {
        // 예외로 알리면 과금된 토큰이 보고에서 빠지고, 다시 부를 실패로 알리면 재시도마다 과금된다
        for (String reason : List.of("OTHER", "MALFORMED_RESPONSE", "SOMETHING_NEW")) {
            LlmResponse response = GeminiResponses.fromStream(new StringReader(chunk("{\"candidates\":[{\"content\":{\"parts\":"
                    + "[{\"text\":\"{}\"}]},\"finishReason\":\"" + reason + "\"}],\"usageMetadata\":{\"promptTokenCount\":100,"
                    + "\"candidatesTokenCount\":5}}")));
            assertEquals(LlmResponse.Finish.COMPLETE, response.finish(), reason);
            assertEquals("{}", response.json());
            assertEquals(new LlmResponse.Usage(100, 0, 0, 5, 0), response.usage());
        }
        LlmException limited = assertThrows(LlmException.class, () -> GeminiResponses.fromStream(new StringReader(chunk(
                "{\"candidates\":[{\"finishReason\":\"PUP_LIMITED_DISABLED\"}]}"))));
        assertEquals(LlmException.Kind.AUTH, limited.kind());
    }

    @Test
    void 하루_할당량과_이_등급에서_쓸_수_없는_모델의_429는_잔액_한도_문제이고_분당_한도는_호출_한도다() {
        LlmException daily = GeminiResponses.fromHttpError(429, quotaError("GenerateRequestsPerDayPerProjectPerModel", "1000"), null);
        LlmException zero = GeminiResponses.fromHttpError(429, quotaError("GenerateRequestsPerMinutePerProjectPerModel-FreeTier", "0"),
                null);
        LlmException minute = GeminiResponses.fromHttpError(429, quotaError("GenerateRequestsPerMinutePerProjectPerModel", "150"),
                null);

        assertEquals(LlmException.Kind.QUOTA, daily.kind());
        assertEquals(LlmException.Kind.QUOTA, zero.kind());
        assertEquals(LlmException.Kind.RATE_LIMITED, minute.kind());
        assertEquals(Optional.of(Duration.ofSeconds(41)), minute.retryAfter());
    }

    @Test
    void 키_문제의_다른_이유와_서버_처리_시간_초과도_가른다() {
        assertEquals(LlmException.Kind.AUTH, GeminiResponses.fromHttpError(400, "{\"error\":{\"code\":400,\"message\":\"API key "
                + "expired.\",\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                + "\"reason\":\"API_KEY_EXPIRED\"}]}}", null).kind());
        assertEquals(LlmException.Kind.INTERRUPTED, GeminiResponses.fromHttpError(504, "{\"error\":{\"code\":504,"
                + "\"message\":\"Deadline expired\",\"status\":\"DEADLINE_EXCEEDED\"}}", null).kind(), "다시 불러도 같은 시간에 걸린다");
        assertEquals(LlmException.Kind.UNAVAILABLE, GeminiResponses.fromHttpError(504, "<html>Gateway Timeout</html>", null).kind(),
                "중간 장비의 504는 다시 부른다");
    }

    @Test
    void 스트림_중간의_오류는_HTTP_오류와_똑같이_가르고_대기_시간을_읽는다() {
        LlmException limited = streamError("{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"slow\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"30s\"}]}}");
        assertEquals(LlmException.Kind.RATE_LIMITED, limited.kind());
        assertEquals(Optional.of(Duration.ofSeconds(30)), limited.retryAfter());
        assertEquals("Gemini API 응답 스트림 오류 (RESOURCE_EXHAUSTED): slow", limited.getMessage());
        assertEquals(LlmException.Kind.QUOTA, streamError(
                "{\"error\":{\"code\":402,\"status\":\"RESOURCE_EXHAUSTED\",\"message\":\"credits depleted\"}}").kind());
        assertEquals(LlmException.Kind.AUTH, streamError(
                "{\"error\":{\"code\":400,\"status\":\"FAILED_PRECONDITION\",\"message\":\"region\"}}").kind());
        assertEquals(LlmException.Kind.INTERRUPTED, streamError(
                "{\"error\":{\"status\":\"DEADLINE_EXCEEDED\",\"message\":\"deadline\"}}").kind(), "숫자 코드가 없으면 상태 이름으로 가른다");
        assertEquals(LlmException.Kind.UNAVAILABLE, streamError(
                "{\"error\":{\"code\":503,\"status\":\"UNAVAILABLE\",\"message\":\"overloaded\"}}").kind());
        // 숫자 코드가 없어도 상태 이름을 HTTP 상태 코드로 바꿔 HTTP 오류와 같은 상세 판정을 거친다
        assertEquals(LlmException.Kind.SETTINGS, streamError(
                "{\"error\":{\"status\":\"NOT_FOUND\",\"message\":\"models/x is not found\"}}").kind());
        assertEquals(LlmException.Kind.SETTINGS, streamError(
                "{\"error\":{\"code\":\"404\",\"status\":\"NOT_FOUND\",\"message\":\"models/x is not found\"}}").kind(),
                "문자열로 온 숫자 코드도 읽는다");
        assertEquals(LlmException.Kind.AUTH, streamError("{\"error\":{\"status\":\"INVALID_ARGUMENT\",\"message\":\"key\","
                + "\"details\":[{\"reason\":\"API_KEY_INVALID\"}]}}").kind());
        assertEquals(LlmException.Kind.UNAVAILABLE, streamError(
                "{\"error\":{\"status\":\"ABORTED\",\"message\":\"conflict\"}}").kind());
        assertEquals(LlmException.Kind.UNAVAILABLE, GeminiResponses.statusKind(409, MissingNode.getInstance()));
    }

    @Test
    void 틀린_키의_400은_키_오류이고_선불_잔액_소진은_한도_문제이며_재시도_대기는_오류_상세에서_읽는다() {
        LlmException invalidKey = GeminiResponses.fromHttpError(400, "{\"error\":{\"code\":400,\"message\":\"API key not valid.\","
                + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.ErrorInfo\","
                + "\"reason\":\"API_KEY_INVALID\"}]}}", null);
        assertEquals(LlmException.Kind.AUTH, invalidKey.kind());
        assertEquals("Gemini API 오류 (HTTP 400): INVALID_ARGUMENT: API key not valid.", invalidKey.getMessage());
        assertEquals(LlmException.Kind.BAD_REQUEST, GeminiResponses.fromHttpError(400,
                "{\"error\":{\"code\":400,\"message\":\"bad\",\"status\":\"INVALID_ARGUMENT\"}}", null).kind());
        assertEquals(LlmException.Kind.QUOTA, GeminiResponses.fromHttpError(402, "{}", null).kind());
        assertEquals(LlmException.Kind.SETTINGS, GeminiResponses.fromHttpError(404, "{}", null).kind());
        LlmException limited = GeminiResponses.fromHttpError(429, "{\"error\":{\"code\":429,\"status\":\"RESOURCE_EXHAUSTED\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"30s\"}]}}", null);
        assertEquals(LlmException.Kind.RATE_LIMITED, limited.kind());
        assertEquals(Optional.of(Duration.ofSeconds(30)), limited.retryAfter());
        assertEquals(LlmException.Kind.UNAVAILABLE, GeminiResponses.fromHttpError(503, "", null).kind());
    }

    @Test
    void 무료_등급을_쓸_수_없는_지역은_키_오류이고_모델이_받지_않는_생각_수준은_설정_오류다() {
        LlmException region = GeminiResponses.fromHttpError(400, "{\"error\":{\"code\":400,\"message\":\"Gemini API free tier "
                + "is not available in your country. Please enable billing on your project in Google AI Studio.\","
                + "\"status\":\"FAILED_PRECONDITION\"}}", null);
        LlmException thinking = GeminiResponses.fromHttpError(400, "{\"error\":{\"code\":400,\"message\":\"Thinking level "
                + "MINIMAL is not supported for this model.\",\"status\":\"INVALID_ARGUMENT\"}}", null);
        LlmException field = GeminiResponses.fromHttpError(400, "{\"error\":{\"code\":400,\"message\":\"Invalid value\","
                + "\"status\":\"INVALID_ARGUMENT\",\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.BadRequest\","
                + "\"fieldViolations\":[{\"field\":\"generation_config.thinking_config.thinking_level\"}]}]}}", null);

        assertEquals(LlmException.Kind.AUTH, region.kind());
        assertEquals(LlmException.Kind.SETTINGS, thinking.kind());
        assertEquals(LlmException.Kind.SETTINGS, field.kind());
    }

    private static LlmException streamError(String data) {
        return assertThrows(LlmException.class, () -> GeminiResponses.fromStream(new StringReader(chunk(data))));
    }

    private static String quotaError(String quotaId, String quotaValue) {
        return "{\"error\":{\"code\":429,\"message\":\"You exceeded your current quota\",\"status\":\"RESOURCE_EXHAUSTED\","
                + "\"details\":[{\"@type\":\"type.googleapis.com/google.rpc.QuotaFailure\",\"violations\":[{\"quotaMetric\":"
                + "\"generativelanguage.googleapis.com/generate_content_requests\",\"quotaId\":\"" + quotaId + "\",\"quotaValue\":\""
                + quotaValue + "\"}]},{\"@type\":\"type.googleapis.com/google.rpc.RetryInfo\",\"retryDelay\":\"41s\"}]}}";
    }

    private static String chunk(String data) {
        return "data: " + data + "\r\n\r\n";
    }
}
