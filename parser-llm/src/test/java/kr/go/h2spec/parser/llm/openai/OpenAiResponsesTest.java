package kr.go.h2spec.parser.llm.openai;

import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiResponsesTest {

    @Test
    void 글_조각을_잇고_마지막_이벤트에서_모델과_사용량을_읽어_캐시_몫을_입력에서_뺀다() throws Exception {
        String stream = event("response.created", "{\"type\":\"response.created\",\"response\":{\"status\":\"in_progress\"}}")
                + event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"a\\\":\"}")
                + event("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"1}\"}")
                + event("response.completed", "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                        + "\"model\":\"gpt-6.1-sol\",\"usage\":{\"input_tokens\":12000,\"input_tokens_details\":"
                        + "{\"cached_tokens\":8000,\"cache_write_tokens\":1000},\"output_tokens\":3000,"
                        + "\"output_tokens_details\":{\"reasoning_tokens\":500}}}}");

        LlmResponse response = OpenAiResponses.fromStream(new StringReader(stream));

        assertEquals("{\"a\":1}", response.json());
        assertEquals("gpt-6.1-sol", response.model());
        assertEquals(LlmResponse.Finish.COMPLETE, response.finish());
        assertEquals(new LlmResponse.Usage(3000, 8000, 1000, 3000, 500), response.usage());
    }

    @Test
    void 출력_한도에_걸린_미완료는_잘림이고_내용_거르기와_거절_조각은_거절이며_실패_이벤트는_예외다() throws Exception {
        String truncated = event("d", "{\"type\":\"response.output_text.delta\",\"delta\":\"{\\\"a\\\"\"}")
                + event("i", "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"usage\":null,"
                        + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}");
        String filtered = event("i", "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"content_filter\"}}}");
        String refusal = event("r", "{\"type\":\"response.refusal.delta\",\"delta\":\"할 수 없습니다\"}")
                + event("c", "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}");
        String failed = event("f", "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\","
                + "\"error\":{\"code\":\"server_error\",\"message\":\"서버 오류\"}}}");

        LlmResponse cut = OpenAiResponses.fromStream(new StringReader(truncated));
        assertEquals(LlmResponse.Finish.TRUNCATED, cut.finish());
        assertEquals("{\"a\"", cut.json(), "잘린 글은 그대로 넘겨 다 적힌 오퍼레이션을 건지게 한다");
        assertEquals(new LlmResponse.Usage(0, 0, 0, 0, 0), cut.usage(), "사용량이 null이면 0이다");
        assertEquals(LlmResponse.Finish.REFUSED, OpenAiResponses.fromStream(new StringReader(filtered)).finish());
        assertEquals(LlmResponse.Finish.REFUSED, OpenAiResponses.fromStream(new StringReader(refusal)).finish());
        LlmException e = assertThrows(LlmException.class, () -> OpenAiResponses.fromStream(new StringReader(failed)));
        assertEquals(LlmException.Kind.UNAVAILABLE, e.kind());
        assertEquals(LlmException.Kind.INTERRUPTED, assertThrows(LlmException.class, () -> OpenAiResponses.fromStream(
                new StringReader(event("d", "{\"type\":\"response.output_text.delta\",\"delta\":\"{\"}")))).kind(),
                "마지막 이벤트 없이 끝나면 끊긴 것이라 다시 부르지 않는다");
    }

    @Test
    void 사용_정책으로_실패한_응답은_토큰을_세는_거절이고_데이터_보관_지역_불일치는_설정_오류다() throws Exception {
        String policy = event("f", "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"model\":\"gpt-6.1-sol\","
                + "\"error\":{\"code\":\"bio_policy\",\"message\":\"policy\"},\"usage\":{\"input_tokens\":100,"
                + "\"output_tokens\":30,\"output_tokens_details\":{\"reasoning_tokens\":30}}}}");
        String residency = event("f", "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\","
                + "\"error\":{\"code\":\"data_residency_mismatch\",\"message\":\"region\"}}}");

        LlmResponse refused = OpenAiResponses.fromStream(new StringReader(policy));

        assertEquals(LlmResponse.Finish.REFUSED, refused.finish());
        assertEquals(new LlmResponse.Usage(100, 0, 0, 30, 30), refused.usage(), "다시 부르지 않고 과금된 토큰을 알린다");
        assertEquals(LlmException.Kind.SETTINGS, assertThrows(LlmException.class,
                () -> OpenAiResponses.fromStream(new StringReader(residency))).kind());
    }

    @Test
    void 오류_이벤트는_명세의_평평한_꼴과_error_아래에_넣은_꼴을_둘_다_읽는다() {
        LlmException flat = assertThrows(LlmException.class, () -> OpenAiResponses.fromStream(new StringReader(event("error",
                "{\"type\":\"error\",\"code\":\"rate_limit_exceeded\",\"message\":\"slow down\",\"param\":null}"))));
        LlmException nested = assertThrows(LlmException.class, () -> OpenAiResponses.fromStream(new StringReader(event("error",
                "{\"type\":\"error\",\"error\":{\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\","
                        + "\"message\":\"No credit\"}}"))));

        assertEquals(LlmException.Kind.RATE_LIMITED, flat.kind());
        assertEquals("OpenAI API 응답 스트림 오류 (rate_limit_exceeded): slow down", flat.getMessage());
        assertEquals(LlmException.Kind.QUOTA, nested.kind());
        assertEquals("OpenAI API 응답 스트림 오류 (insufficient_quota): No credit", nested.getMessage());
        assertEquals(LlmException.Kind.BAD_REQUEST, assertThrows(LlmException.class, () -> OpenAiResponses.fromStream(
                new StringReader(event("error", "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"code\":null,\"message\":\"bad\"}}")))).kind(), "코드가 null이면 종류로 가른다");
    }

    @Test
    void 모르는_미완료_이유는_응답을_끝까지_받아_과금됐으므로_출력과_토큰을_넘겨_JSON으로_판단하게_한다() throws Exception {
        LlmResponse response = OpenAiResponses.fromStream(new StringReader(
                event("d", "{\"type\":\"response.output_text.delta\",\"delta\":\"{}\"}")
                        + event("i", "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                        + "\"incomplete_details\":{\"reason\":\"max_messages\"},\"usage\":{\"input_tokens\":50,\"output_tokens\":7}}}")));

        assertEquals(LlmResponse.Finish.COMPLETE, response.finish());
        assertEquals("{}", response.json());
        assertEquals(new LlmResponse.Usage(50, 0, 0, 7, 0), response.usage());
    }

    @Test
    void HTTP_오류는_상태_코드로_가르고_429는_오류_코드로_호출_한도와_잔액_문제를_가른다() {
        assertEquals(LlmException.Kind.AUTH, OpenAiResponses.fromHttpError(401,
                "{\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\",\"code\":\"invalid_api_key\"}}",
                null).kind());
        LlmException missing = OpenAiResponses.fromHttpError(404,
                "{\"error\":{\"message\":\"The model does not exist\",\"code\":\"model_not_found\"}}", null);
        assertEquals(LlmException.Kind.SETTINGS, missing.kind());
        assertEquals("OpenAI API 오류 (HTTP 404): model_not_found: The model does not exist", missing.getMessage());
        assertEquals(LlmException.Kind.RATE_LIMITED, OpenAiResponses.fromHttpError(429,
                "{\"error\":{\"message\":\"Rate limit\",\"type\":\"rate_limit_error\",\"code\":\"rate_limit_exceeded\"}}",
                null).kind());
        assertEquals(LlmException.Kind.QUOTA, OpenAiResponses.fromHttpError(429,
                "{\"error\":{\"message\":\"No credit\",\"type\":\"insufficient_quota\",\"code\":\"credit_balance_exhausted\"}}",
                null).kind(), "잔액 문제는 기다려도 풀리지 않는다");
        assertEquals(LlmException.Kind.QUOTA, OpenAiResponses.fromHttpError(402,
                "{\"error\":{\"message\":\"Insufficient credits\",\"code\":402}}", null).kind(),
                "게이트웨이가 402로 알리는 잔액 부족도 기다려도 풀리지 않는다");
        assertEquals(LlmException.Kind.BAD_REQUEST, OpenAiResponses.fromHttpError(429,
                "{\"error\":{\"message\":\"Request too large for gpt-6.1-sol in organization org-x on tokens per min (TPM): "
                        + "Limit 100000, Requested 131000.\",\"type\":\"tokens\",\"code\":\"rate_limit_exceeded\"}}",
                null).kind(), "요청 하나가 분당 한도를 넘으면 기다려도 같은 요청은 늘 넘지만, 작은 문서는 통과할 수 있다");
        assertEquals(LlmException.Kind.UNAVAILABLE, OpenAiResponses.fromHttpError(503, "<html>busy</html>", null).kind());
        assertEquals(LlmException.Kind.BAD_REQUEST, OpenAiResponses.fromHttpError(400, "{}", null).kind());
        assertEquals(LlmException.Kind.AUTH, OpenAiResponses.fromHttpError(403,
                "{\"error\":{\"message\":\"Country, region, or territory not supported\",\"code\":\"unsupported_country_region_territory\"}}",
                null).kind(), "지원하지 않는 지역은 다른 문서도 같은 이유로 실패한다");
    }

    @Test
    void 모델이_받지_않는_추론_깊이의_400만_설정_오류로_가른다() {
        LlmException effort = OpenAiResponses.fromHttpError(400, "{\"error\":{\"message\":\"Unsupported value: 'none' is not "
                + "supported with this model.\",\"type\":\"invalid_request_error\",\"param\":\"reasoning.effort\","
                + "\"code\":\"unsupported_value\"}}", null);
        LlmException other = OpenAiResponses.fromHttpError(400, "{\"error\":{\"message\":\"Invalid schema\","
                + "\"type\":\"invalid_request_error\",\"param\":\"text.format.schema\",\"code\":\"invalid_json_schema\"}}", null);

        assertEquals(LlmException.Kind.SETTINGS, effort.kind());
        assertEquals(LlmException.Kind.BAD_REQUEST, other.kind(), "우리가 만든 스키마의 오류는 요청 오류로 둔다");
        for (String param : List.of("service_tier", "max_output_tokens", "model", "input[0].content[0].prompt_cache_breakpoint")) {
            assertEquals(LlmException.Kind.SETTINGS, OpenAiResponses.fromHttpError(400,
                    "{\"error\":{\"message\":\"x\",\"type\":\"invalid_request_error\",\"param\":\"" + param + "\"}}", null).kind(),
                    param + "는 문서마다 똑같이 되풀이된다");
        }
        assertEquals(LlmException.Kind.AUTH, OpenAiResponses.fromHttpError(400, "{\"error\":{\"message\":\"Your organization must "
                + "be verified to stream this model.\",\"param\":\"stream\",\"code\":\"unsupported_value\"}}", null).kind());
    }

    private static String event(String name, String data) {
        return "event: " + name + "\ndata: " + data + "\n\n";
    }
}
