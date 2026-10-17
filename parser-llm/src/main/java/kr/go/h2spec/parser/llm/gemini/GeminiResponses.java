package kr.go.h2spec.parser.llm.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import kr.go.h2spec.parser.llm.client.LlmException;
import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import kr.go.h2spec.parser.llm.client.LlmResponse;
import kr.go.h2spec.parser.llm.client.LlmResponse.Finish;
import kr.go.h2spec.parser.llm.client.LlmResponse.Usage;
import kr.go.h2spec.parser.llm.client.SseReader;

import java.io.IOException;
import java.io.Reader;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini API의 응답을 {@link LlmResponse}와 {@link LlmException}으로 바꾼다.
 * <ul>
 *   <li>SSE의 {@code data}마다 응답 객체 하나가 온다. 출력 JSON은 {@code candidates[0].content.parts[].text}를 이어 얻고, 생각 조각
 *       ({@code thought: true})은 버린다.</li>
 *   <li>마지막 조각에 {@code finishReason}이 온다. 따로 끝을 알리는 이벤트가 없어, 끝나는 이유를 받지 못한 채 본문이 끝나면 끊긴 것이다.</li>
 *   <li>사용량({@code usageMetadata})과 모델({@code modelVersion})은 조각마다 오므로 마지막 값을 쓴다. 더하지 않는다.
 *       {@code promptTokenCount}에는 캐시에서 읽은 입력이 들어 있어 빼서 옮기고, 생각 토큰({@code thoughtsTokenCount})은
 *       {@code candidatesTokenCount}와 따로 오지만 출력 단가로 과금되므로 더해서 옮긴다.</li>
 *   <li>출력 한도({@code MAX_TOKENS})는 잘림, 안전·인용·차단 계열과 프롬프트 차단({@code promptFeedback.blockReason})은 거절이다.
 *       그 밖의 끝난 이유는 출력 JSON으로 판단하게 넘긴다({@link #finish}).</li>
 * </ul>
 */
public final class GeminiResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** JSON이 아닌 오류 본문(프록시가 낸 HTML 등)에서 메시지로 옮기는 최대 글자 수 */
    private static final int MAX_ERROR_TEXT = 200;
    /** 회사가 내용을 이유로 출력을 멈춘 끝 이유 */
    private static final Set<String> REFUSALS = Set.of("SAFETY", "RECITATION", "LANGUAGE", "BLOCKLIST", "PROHIBITED_CONTENT",
            "SPII", "IMAGE_SAFETY", "ESCALATION");
    /** 오류 상세의 RetryInfo.retryDelay ("30s", "1.5s") */
    private static final Pattern SECONDS = Pattern.compile("(\\d+(?:\\.\\d+)?)s");

    private GeminiResponses() {
    }

    /**
     * 스트리밍 응답(SSE) 본문을 끝까지 읽는다.
     *
     * @param body 응답 본문
     * @return 글 조각을 이은 출력, 모델, 사용량, 끝난 방식
     * @throws IOException  본문을 읽지 못했을 때
     * @throws LlmException 스트림 중간에 오류가 왔거나, 끝나는 이유 없이 끝났거나, 조각을 해석할 수 없을 때
     */
    public static LlmResponse fromStream(Reader body) throws IOException, LlmException {
        SseReader events = new SseReader(body);
        StringBuilder text = new StringBuilder();
        String finishReason = "";
        String blockReason = "";
        String model = "";
        JsonNode usage = MissingNode.getInstance();
        for (SseReader.Event event = events.next(); event != null; event = events.next()) {
            JsonNode chunk = parse(event);
            if (chunk.path("error").isObject()) {
                throw streamError(chunk.path("error"));
            }
            JsonNode candidate = chunk.path("candidates").path(0);
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (!part.path("thought").asBoolean(false)) {
                    text.append(part.path("text").asText(""));
                }
            }
            finishReason = candidate.path("finishReason").asText(finishReason);
            blockReason = chunk.path("promptFeedback").path("blockReason").asText(blockReason);
            model = chunk.path("modelVersion").asText(model);
            if (chunk.path("usageMetadata").isObject()) {
                usage = chunk.path("usageMetadata");
            }
        }
        if (!blockReason.isEmpty()) {
            return new LlmResponse(text.toString(), model, usage(usage), Finish.REFUSED);
        }
        if (finishReason.isEmpty()) {
            // 정상 응답은 마지막 조각에 끝나는 이유가 있다. 없이 본문이 끝났으면 연결이 중간에 끊긴 것이다(서버는 스트림을 20분에 닫는다)
            throw new LlmException(Kind.INTERRUPTED, "Gemini API 응답 스트림이 끝나는 이유 없이 끝났습니다");
        }
        return new LlmResponse(text.toString(), model, usage(usage), finish(finishReason));
    }

    /**
     * 200이 아닌 HTTP 응답을 실패 종류로 바꾼다. Gemini는 틀린 키를 400으로 돌려주므로 오류 상세의 이유({@code API_KEY_INVALID} 등)로
     * 키 오류를 가르고, 429는 오류 상세의 {@code QuotaFailure}로 하루 할당량을 가른다. 대기 시간은 {@code retry-after} 머리가 없으면
     * 오류 상세의 {@code RetryInfo.retryDelay}를 쓴다.
     *
     * @param statusCode HTTP 상태 코드
     * @param body       오류 응답 본문. 읽지 못했으면 빈 문자열이나 null
     * @param retryAfter {@code retry-after} 머리의 대기 시간. 없으면 null
     * @return 던질 예외
     */
    public static LlmException fromHttpError(int statusCode, String body, Duration retryAfter) {
        JsonNode error = readQuietly(body == null ? "" : body).path("error");
        String detail = error.isObject() ? describe(error) : oneLine(body == null ? "" : body);
        Duration wait = retryAfter != null ? retryAfter : retryDelay(error);
        return new LlmException(statusKind(statusCode, error),
                "Gemini API 오류 (HTTP " + statusCode + ")" + (detail.isEmpty() ? "" : ": " + detail), null, wait);
    }

    static Kind statusKind(int statusCode, JsonNode error) {
        String status = error.path("status").asText("");
        // FAILED_PRECONDITION(400)은 무료 등급을 쓸 수 없는 지역처럼 계정·지역 문제다. 다른 문서도 같은 이유로 실패한다
        if (statusCode == 401 || statusCode == 403 || hasKeyReason(error) || "FAILED_PRECONDITION".equals(status)) {
            return Kind.AUTH;
        }
        // 서버가 요청을 처리하다 시간이 다 됐다. 다시 불러도 같은 시간에 걸리고, 처리한 만큼 비용이 겹칠 수 있다
        if ("DEADLINE_EXCEEDED".equals(status)) {
            return Kind.INTERRUPTED;
        }
        // 그 모델이 받지 않는 생각 수준은 문서마다 같은 400이 되므로 설정 오류로 보고 실행을 멈추게 한다. minimal은 보내기 전에 막지만
        // 다른 모델(--model)은 받는 범위가 다를 수 있다
        if (statusCode == 400 && mentionsThinking(error)) {
            return Kind.SETTINGS;
        }
        // 선불 잔액이 다 떨어졌다. 기다려도 풀리지 않는다
        if (statusCode == 402) {
            return Kind.QUOTA;
        }
        // 주소의 경로는 모델마다 하나라 404는 모르는 모델이거나 주소(프록시 경로)가 틀린 것이다. 다른 문서를 불러도 똑같이 실패한다
        if (statusCode == 404) {
            return Kind.SETTINGS;
        }
        if (statusCode == 429) {
            return isLongQuota(error) ? Kind.QUOTA : Kind.RATE_LIMITED;
        }
        // 408·409(ABORTED)는 공식 SDK도 다시 부른다
        if (statusCode == 408 || statusCode == 409 || statusCode >= 500) {
            return Kind.UNAVAILABLE;
        }
        return Kind.BAD_REQUEST;
    }

    /**
     * 끝난 이유를 끝난 방식으로 바꾼다. 잘림·거절이 아닌 이유({@code OTHER}, {@code MALFORMED_RESPONSE}, 모르는 이유)는
     * {@link Finish#COMPLETE}로 넘겨 출력 JSON으로 판단하게 한다. 출력은 스키마로 강제한 JSON이라 끝까지 적혀야만 읽히므로, 읽히면
     * 다 된 출력이고, 읽히지 않으면 llm 엔진이 JSON을 읽지 못한 응답처럼 한 번 더 부른 뒤 실패로 센다. 예외로 알리면 이미 과금된 토큰이
     * 보고에서 빠지고, 다시 부를 실패로 알리면 재시도마다 과금되면서도 보고되지 않는다.
     *
     * @throws LlmException 금지된 사용 정책 위반으로 계정이 제한되었을 때. 모든 문서가 같은 이유로 실패한다
     */
    static Finish finish(String finishReason) throws LlmException {
        if ("STOP".equals(finishReason)) {
            return Finish.COMPLETE;
        }
        // CONTINUATION은 gemini-4-argon에 출력 상한을 6만 5천여 토큰보다 크게 줄 때만 오는데 llm 엔진은 그렇게 보내지 않는다. 오면 잘림으로 본다
        if ("MAX_TOKENS".equals(finishReason) || "CONTINUATION".equals(finishReason)) {
            return Finish.TRUNCATED;
        }
        if (REFUSALS.contains(finishReason)) {
            return Finish.REFUSED;
        }
        if ("PUP_LIMITED_DISABLED".equals(finishReason)) {
            throw new LlmException(Kind.AUTH, "Gemini API가 금지된 사용 정책 위반으로 계정을 제한했습니다 (" + finishReason + ")");
        }
        return Finish.COMPLETE;
    }

    static Usage usage(JsonNode usage) {
        long prompt = usage.path("promptTokenCount").asLong();
        long cached = usage.path("cachedContentTokenCount").asLong();
        long thoughts = usage.path("thoughtsTokenCount").asLong();
        return new Usage(Math.max(0, prompt - cached), cached, 0,
                usage.path("candidatesTokenCount").asLong() + thoughts, thoughts);
    }

    private static boolean mentionsThinking(JsonNode error) {
        if (error.path("message").asText("").toLowerCase(Locale.ROOT).contains("thinking")) {
            return true;
        }
        for (JsonNode detail : error.path("details")) {
            for (JsonNode violation : detail.path("fieldViolations")) {
                if (violation.path("field").asText("").contains("thinking")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** {@code API_KEY_INVALID}, {@code API_KEY_EXPIRED}, {@code API_KEY_SERVICE_BLOCKED} 같은 키 문제 */
    private static boolean hasKeyReason(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            if (detail.path("reason").asText("").startsWith("API_KEY_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 기다려도 오늘 안에는 풀리지 않는 할당량. 하루 요청 수(태평양 시간 자정에 풀린다)와, 이 등급에서 쓸 수 없는 모델(한도 0)이다.
     * 분당 요청·토큰 수와 10분 단위 지출 한도는 곧 풀리므로 호출 한도로 둔다
     */
    private static boolean isLongQuota(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            for (JsonNode violation : detail.path("violations")) {
                if (violation.path("quotaId").asText("").contains("PerDay") || "0".equals(violation.path("quotaValue").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Duration retryDelay(JsonNode error) {
        for (JsonNode detail : error.path("details")) {
            Matcher matcher = SECONDS.matcher(detail.path("retryDelay").asText(""));
            if (matcher.matches()) {
                return Duration.ofMillis(Math.round(Double.parseDouble(matcher.group(1)) * 1000));
            }
        }
        return null;
    }

    private static JsonNode parse(SseReader.Event event) throws LlmException {
        try {
            JsonNode data = MAPPER.readTree(event.data());
            return data == null ? MissingNode.getInstance() : data;
        } catch (JsonProcessingException e) {
            // 서버나 중간 프록시가 스트림을 망가뜨린 경우다. 회사는 끝까지 생성해 과금할 수 있어 끊긴 스트림처럼 다시 부르지 않는다
            throw new LlmException(Kind.INTERRUPTED, "Gemini API 응답 스트림의 조각을 해석할 수 없습니다", e);
        }
    }

    /**
     * HTTP 200으로 시작한 스트림 중간의 오류. 오류 객체가 HTTP 오류와 같은 꼴이라 HTTP 오류와 똑같이 가른다. 숫자 코드가 없으면 상태
     * 이름을 그 이름의 HTTP 상태 코드로 바꿔 가르므로, 키·생각 수준·할당량 같은 상세 판정도 HTTP 오류와 같다. 서버 오류와 모르는 상태는
     * 회사가 스스로 생성을 멈춘 것이라 다시 부른다
     */
    private static LlmException streamError(JsonNode error) {
        String status = error.path("status").asText("");
        JsonNode code = error.path("code");
        int statusCode = code.isInt() || (code.isTextual() && code.asText().matches("\\d{3}"))
                ? code.asInt() : statusCodeOf(status);
        return new LlmException(statusKind(statusCode, error),
                "Gemini API 응답 스트림 오류 (" + status + "): " + error.path("message").asText(""), null, retryDelay(error));
    }

    /** Google API 상태 이름에 대응하는 HTTP 상태 코드. 모르는 이름은 서버 오류(500)로 본다 */
    private static int statusCodeOf(String status) {
        return switch (status) {
            case "INVALID_ARGUMENT", "FAILED_PRECONDITION", "OUT_OF_RANGE" -> 400;
            case "UNAUTHENTICATED" -> 401;
            case "PERMISSION_DENIED" -> 403;
            case "NOT_FOUND" -> 404;
            case "ALREADY_EXISTS", "ABORTED" -> 409;
            case "RESOURCE_EXHAUSTED" -> 429;
            case "UNIMPLEMENTED" -> 501;
            case "UNAVAILABLE" -> 503;
            case "DEADLINE_EXCEEDED" -> 504;
            default -> 500;
        };
    }

    private static String describe(JsonNode error) {
        String status = error.path("status").asText("");
        return (status.isEmpty() ? "" : status + ": ") + error.path("message").asText("");
    }

    private static String oneLine(String body) {
        String line = body.strip().replaceAll("\\s+", " ");
        return line.length() > MAX_ERROR_TEXT ? line.substring(0, MAX_ERROR_TEXT) + "…" : line;
    }

    private static JsonNode readQuietly(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null ? MissingNode.getInstance() : root;
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }
}
