package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Claude Messages API의 응답을 {@link LlmResponse}와 {@link LlmException}으로 바꾼다.
 * <p>
 * {@link ClaudeClient}가 스트리밍 응답을 해석할 때 쓴다. HTTP 호출 없이 시험할 수 있게 따로 둔다.
 * <ul>
 *   <li>출력 JSON은 글 블록에만 온다. thinking 블록(요약이든 빈 글이든)과 서명은 버린다.</li>
 *   <li>사용량은 {@link Usage}의 뜻에 맞춰 옮긴다. Claude의 {@code input_tokens}는 이미 캐시 읽기·쓰기를 뺀 값이고,
 *       {@code output_tokens}에는 thinking이 들어 있다.</li>
 *   <li>거절({@code stop_reason: refusal})은 HTTP 200으로 오므로 오류가 아니라 {@link Finish#REFUSED}다.</li>
 * </ul>
 */
public final class ClaudeResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** JSON이 아닌 오류 본문(프록시가 낸 HTML 등)에서 메시지로 옮기는 최대 글자 수 */
    private static final int MAX_ERROR_TEXT = 200;

    private ClaudeResponses() {
    }

    /**
     * 스트리밍 응답(SSE) 본문을 끝까지 읽는다. {@code message_stop}을 받으면 그 뒤는 읽지 않는다.
     *
     * @param body 응답 본문
     * @return 글 조각을 이은 출력, 모델, 사용량, 끝난 방식
     * @throws IOException 본문을 읽지 못했을 때
     * @throws LlmException 스트림 중간에 오류 이벤트가 왔거나, {@code message_stop} 전에 끝났거나, 이벤트를 해석할 수 없을 때
     */
    public static LlmResponse fromStream(Reader body) throws IOException, LlmException {
        SseReader events = new SseReader(body);
        StreamedMessage message = new StreamedMessage();
        for (SseReader.Event event = events.next(); event != null; event = events.next()) {
            JsonNode data = parse(event);
            switch (data.path("type").asText(event.name())) {
                case "message_start" -> message.start(data.path("message"));
                case "content_block_start" -> message.blockStart(data.path("content_block"));
                case "content_block_delta" -> message.delta(data.path("delta"));
                case "message_delta" -> message.end(data);
                case "message_stop" -> {
                    return message.response();
                }
                case "error" -> throw streamError(data.path("error"));
                default -> {
                    // ping, content_block_stop과, API 버전 정책상 앞으로 더해질 수 있는 이벤트는 버린다
                }
            }
        }
        // 정상 응답은 message_stop으로 끝난다. 그 전에 본문이 끝났으면 연결이 중간에 끊긴 것이다
        throw new LlmException(Kind.INTERRUPTED, "Claude API 응답 스트림이 message_stop 전에 끝났습니다");
    }

    /**
     * 200이 아닌 HTTP 응답을 실패 종류로 바꾼다. 종류는 상태 코드로 정하고, 본문의 오류 종류·메시지·요청 ID는 메시지에 옮긴다.
     *
     * @param statusCode HTTP 상태 코드
     * @param body 오류 응답 본문. 읽지 못했으면 빈 문자열이나 null
     * @param retryAfter {@code retry-after} 머리의 대기 시간. 없으면 null
     * @return 던질 예외
     */
    public static LlmException fromHttpError(int statusCode, String body, Duration retryAfter) {
        JsonNode root = readQuietly(body == null ? "" : body);
        String detail = body == null ? "" : describeError(root, body);
        Kind kind = statusKind(statusCode);
        // 그 모델이 받지 않는 추론 깊이·출력 한도, 잔액 부족("Your credit balance is too low …"), 지정한 사용 한도 도달
        // ("You have reached your specified API usage limits …")은 400으로도 온다. 문서마다 같은 400이 되므로 각각 설정 오류와
        // 잔액·사용 한도 문제로 보고 실행을 멈추게 한다. 입력과 출력 한도의 합이 컨텍스트를 넘는 400은 그 문서만의 문제라 그대로 둔다
        String message = root.path("error").path("message").asText("");
        String lower = message.toLowerCase(Locale.ROOT);
        if (kind == Kind.BAD_REQUEST && (message.contains("effort") || lower.contains("maximum allowed number of output tokens"))) {
            kind = Kind.SETTINGS;
        } else if (kind == Kind.BAD_REQUEST && (lower.contains("credit balance") || lower.contains("usage limit"))) {
            kind = Kind.QUOTA;
        } else if (kind == Kind.RATE_LIMITED && isSpendLimit(body == null ? "" : body)) {
            // 월 지출 한도는 429로 오지만 다음 달까지 풀리지 않는다
            kind = Kind.QUOTA;
        }
        return new LlmException(kind,
                "Claude API 오류 (HTTP " + statusCode + ")" + (detail.isEmpty() ? "" : ": " + detail), null, retryAfter);
    }

    static Kind statusKind(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return Kind.AUTH;
        }
        // billing_error. 잔액이 없다
        if (statusCode == 402) {
            return Kind.QUOTA;
        }
        if (statusCode == 429) {
            return Kind.RATE_LIMITED;
        }
        // 메시지 API의 경로는 하나라 404는 모르는 모델이거나 주소(프록시 경로)가 틀린 것이다. 다른 문서를 불러도 똑같이 실패한다
        if (statusCode == 404) {
            return Kind.SETTINGS;
        }
        // 500·502·503·504와 과부하(529). 5xx는 모두 다시 부른다. 408·409는 공식 SDK도 다시 부른다
        if (statusCode == 408 || statusCode == 409 || statusCode >= 500) {
            return Kind.UNAVAILABLE;
        }
        // 그 밖의 4xx와, 리다이렉트처럼 이 API가 내지 않는 응답은 다시 보내도 같다
        return Kind.BAD_REQUEST;
    }

    /** HTTP 200으로 시작한 스트림 중간의 오류 이벤트는 상태 코드가 없어 오류 종류로 가른다 */
    static Kind errorTypeKind(String errorType) {
        return switch (errorType) {
            case "authentication_error", "permission_error" -> Kind.AUTH;
            case "rate_limit_error" -> Kind.RATE_LIMITED;
            case "not_found_error" -> Kind.SETTINGS;
            case "billing_error" -> Kind.QUOTA;
            case "invalid_request_error", "request_too_large" -> Kind.BAD_REQUEST;
            // overloaded_error·api_error·timeout_error. 스트림 중간의 오류는 거의 일시적인 서버 문제라 모르는 종류도 다시 부른다
            default -> Kind.UNAVAILABLE;
        };
    }

    /**
     * 끝난 이유를 끝난 방식으로 바꾼다. 잘림·거절이 아닌 이유(도구를 쓰지 않는 이 요청에서는 나오지 않는 {@code tool_use}·{@code pause_turn}과
     * 모르는 값)는 {@link Finish#COMPLETE}로 넘겨 출력 JSON으로 판단하게 한다. 출력은 스키마로 강제한 JSON이라 끝까지 적혀야만 읽히므로,
     * 읽히지 않으면 llm 엔진이 JSON을 읽지 못한 응답처럼 한 번 더 부른 뒤 실패로 센다. 예외로 알리면 이미 과금된 토큰이 보고에서 빠진다
     */
    static Finish finish(String stopReason) {
        return switch (stopReason) {
            case "max_tokens" -> Finish.TRUNCATED;
            // 입력과 출력을 합친 길이가 모델의 컨텍스트 창에 닿아 출력이 끊겼다. 출력이 끝나지 않은 것은 max_tokens와 같아 잘림으로 본다
            case "model_context_window_exceeded" -> Finish.TRUNCATED;
            // 안전 분류기가 거절했다. HTTP 200으로 오고, 그때까지의 출력은 스키마를 따르지 않을 수 있다
            case "refusal" -> Finish.REFUSED;
            // end_turn·stop_sequence와 그 밖의 값
            default -> Finish.COMPLETE;
        };
    }

    /** 스트림의 누계 사용량과 메시지 객체의 사용량은 꼴이 같다 */
    private static Usage usage(JsonNode usage) {
        return new Usage(
                // 캐시에서 읽거나 캐시에 쓴 입력을 이미 뺀 값이라 그대로 옮긴다
                usage.path("input_tokens").asLong(),
                usage.path("cache_read_input_tokens").asLong(),
                usage.path("cache_creation_input_tokens").asLong(),
                // thinking 토큰이 들어 있다
                usage.path("output_tokens").asLong(),
                // Claude는 thinking 토큰을 따로 알려 주지 않는다. 0은 "모른다"는 뜻이다
                0);
    }

    private static JsonNode parse(SseReader.Event event) throws LlmException {
        try {
            JsonNode data = MAPPER.readTree(event.data());
            return data == null ? MissingNode.getInstance() : data;
        } catch (JsonProcessingException e) {
            // 서버나 중간 프록시가 스트림을 망가뜨린 경우다. 회사는 끝까지 생성해 과금할 수 있어 끊긴 스트림처럼 다시 부르지 않는다
            throw new LlmException(Kind.INTERRUPTED,
                    "Claude API 응답 스트림의 " + event.name() + " 이벤트를 해석할 수 없습니다", e);
        }
    }

    private static LlmException streamError(JsonNode error) {
        String type = error.path("type").asText("");
        return new LlmException(errorTypeKind(type),
                "Claude API 응답 스트림 오류 (" + type + "): " + error.path("message").asText(""));
    }

    /** 월 지출 한도 도달({@code enforced_spend_limit_reached} 등). 오류 상세의 꼴이 정해져 있지 않아 본문 전체에서 찾는다 */
    private static boolean isSpendLimit(String body) {
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("spend_limit") || lower.contains("spend limit");
    }

    /** 오류 본문에서 종류·메시지·요청 ID를 꺼낸다. JSON이 아니면 앞부분만 한 줄로 옮긴다 */
    private static String describeError(JsonNode root, String body) {
        JsonNode error = root.path("error");
        if (error.isObject()) {
            String detail = error.path("type").asText("") + ": " + error.path("message").asText("");
            String requestId = root.path("request_id").asText("");
            return requestId.isEmpty() ? detail : detail + " (request_id " + requestId + ")";
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() > MAX_ERROR_TEXT ? oneLine.substring(0, MAX_ERROR_TEXT) + "…" : oneLine;
    }

    private static JsonNode readQuietly(String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            return root == null ? MissingNode.getInstance() : root;
        } catch (JsonProcessingException e) {
            return MissingNode.getInstance();
        }
    }

    /** 스트림 이벤트를 모아 메시지 하나를 만든다 */
    private static final class StreamedMessage {

        private final StringBuilder text = new StringBuilder();
        private final ObjectNode usage = MAPPER.createObjectNode();
        private boolean started;
        private String model = "";
        private String stopReason;

        void start(JsonNode message) {
            started = true;
            model = message.path("model").asText("");
            mergeUsage(message.path("usage"));
        }

        void blockStart(JsonNode block) {
            // 글 블록은 보통 빈 글로 시작해 text_delta로 채워진다. 시작부터 글이 있으면 그것도 출력이다
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText(""));
            }
        }

        void delta(JsonNode delta) {
            // thinking_delta·signature_delta는 thinking 블록의 것이라 버린다
            if ("text_delta".equals(delta.path("type").asText())) {
                text.append(delta.path("text").asText(""));
            }
        }

        void end(JsonNode messageDelta) {
            stopReason = messageDelta.path("delta").path("stop_reason").asText(null);
            mergeUsage(messageDelta.path("usage"));
        }

        /** message_delta의 사용량은 그때까지의 누계라 더하지 않고 덮어쓴다. 값이 없는 항목은 message_start의 값을 둔다 */
        private void mergeUsage(JsonNode part) {
            part.fields().forEachRemaining(field -> {
                if (field.getValue().isNumber()) {
                    usage.set(field.getKey(), field.getValue());
                }
            });
        }

        LlmResponse response() throws LlmException {
            if (!started) {
                throw new LlmException(Kind.INTERRUPTED, "Claude API 응답 스트림에 message_start가 없습니다");
            }
            if (stopReason == null) {
                throw new LlmException(Kind.INTERRUPTED, "Claude API 응답 스트림에 stop_reason이 없습니다");
            }
            return new LlmResponse(text.toString(), model, usage(usage), finish(stopReason));
        }
    }
}
