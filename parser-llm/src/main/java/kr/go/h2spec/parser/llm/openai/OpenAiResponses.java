package kr.go.h2spec.parser.llm.openai;

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
import java.util.Set;

/**
 * OpenAI Responses API의 응답을 {@link LlmResponse}와 {@link LlmException}으로 바꾼다.
 * <ul>
 *   <li>출력 JSON은 {@code response.output_text.delta}의 조각을 이어 얻는다. 조각이 없으면 마지막 이벤트의 {@code output[]}에서 읽는다.</li>
 *   <li>상태와 사용량은 마지막 이벤트({@code response.completed}·{@code response.incomplete}·{@code response.failed})의
 *       {@code response}에 있다. 이벤트는 SSE 이름이 아니라 JSON의 {@code type}으로 가른다.</li>
 *   <li>사용량은 {@link Usage}의 뜻에 맞춰 옮긴다. OpenAI의 {@code input_tokens}에는 캐시에서 읽은 입력과 캐시에 쓴 입력이 들어 있어
 *       빼서 옮기고, {@code output_tokens}에는 추론 토큰이 들어 있다.</li>
 *   <li>출력 한도에 걸린 미완료({@code max_output_tokens})는 잘림, 내용 거르기({@code content_filter})와 거절 조각, 사용 정책으로
 *       실패한 응답({@code response.failed}의 {@code bio_policy}·{@code misalignment_policy_violation})은 거절이다. 그 밖의 미완료
 *       이유와 모르는 상태는 출력 JSON으로 판단하게 넘긴다({@link #finish}).</li>
 * </ul>
 */
public final class OpenAiResponses {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** JSON이 아닌 오류 본문(프록시가 낸 HTML 등)에서 메시지로 옮기는 최대 글자 수 */
    private static final int MAX_ERROR_TEXT = 200;
    /** 기다려도 풀리지 않는 429. 잔액이 없거나 사용 한도를 넘었다 */
    private static final Set<String> QUOTA_CODES = Set.of("insufficient_quota", "credit_balance_exhausted",
            "organization_spend_limit_exceeded", "project_spend_limit_exceeded", "organization_usage_limit_exceeded",
            "billing_hard_limit_reached");
    /** 사용 정책 때문에 실패한 응답. 다시 불러도 같고, 회사는 거기까지 생성한 만큼 과금하므로 거절로 보고 토큰을 센다 */
    private static final Set<String> POLICY_CODES = Set.of("bio_policy", "misalignment_policy_violation");

    private OpenAiResponses() {
    }

    /**
     * 스트리밍 응답(SSE) 본문을 끝까지 읽는다. 마지막 이벤트를 받으면 그 뒤는 읽지 않는다.
     *
     * @param body 응답 본문
     * @return 글 조각을 이은 출력, 모델, 사용량, 끝난 방식
     * @throws IOException  본문을 읽지 못했을 때
     * @throws LlmException 스트림 중간에 오류가 왔거나, 마지막 이벤트 전에 끝났거나, 이벤트를 해석할 수 없을 때
     */
    public static LlmResponse fromStream(Reader body) throws IOException, LlmException {
        SseReader events = new SseReader(body);
        StringBuilder text = new StringBuilder();
        boolean refused = false;
        for (SseReader.Event event = events.next(); event != null; event = events.next()) {
            // Responses API는 [DONE]을 보내지 않는다. 주소를 바꿔 쓰는 호환 프록시가 마지막 이벤트 없이 [DONE]만 보내면 끝난 방식과
            // 사용량을 알 수 없으므로 거기서 읽기를 멈추고 끊긴 스트림으로 본다
            if ("[DONE]".equals(event.data().strip())) {
                break;
            }
            JsonNode data = parse(event);
            if (data.path("error").isObject() && !data.has("type")) {
                throw streamError(data.path("error"));
            }
            switch (data.path("type").asText(event.name())) {
                case "response.output_text.delta" -> text.append(data.path("delta").asText(""));
                case "response.refusal.delta", "response.refusal.done" -> refused = true;
                case "response.completed", "response.incomplete" -> {
                    return response(data.path("response"), text, refused);
                }
                case "response.failed" -> {
                    JsonNode response = data.path("response");
                    if (POLICY_CODES.contains(response.path("error").path("code").asText(""))) {
                        return new LlmResponse(text.toString(), response.path("model").asText(""),
                                usage(response.path("usage")), Finish.REFUSED);
                    }
                    throw streamError(response.path("error"));
                }
                // API 명세의 오류 이벤트는 code·message가 최상위에 있다. error 아래에 넣어 보내는 경우도 있다고 알려져 둘 다 읽는다
                case "error" -> throw streamError(data.path("error").isObject() ? data.path("error") : data);
                default -> {
                    // 시작·진행·조각 경계 이벤트와, API 버전 정책상 앞으로 더해질 수 있는 이벤트는 버린다
                }
            }
        }
        // 정상 응답은 마지막 이벤트로 끝난다. 그 전에 본문이 끝났으면 연결이 중간에 끊긴 것이다
        throw new LlmException(Kind.INTERRUPTED, "OpenAI API 응답 스트림이 마지막 이벤트 전에 끝났습니다");
    }

    /**
     * 200이 아닌 HTTP 응답을 실패 종류로 바꾼다. 종류는 상태 코드로 정하고, 429는 오류 코드로 호출 한도와 잔액·사용 한도 문제를 가르고,
     * 400은 문제가 된 요청 필드({@code error.param})로 모든 문서가 같이 실패하는 설정 문제를 가른다.
     *
     * @param statusCode HTTP 상태 코드
     * @param body       오류 응답 본문. 읽지 못했으면 빈 문자열이나 null
     * @param retryAfter {@code retry-after} 머리의 대기 시간. 없으면 null
     * @return 던질 예외
     */
    public static LlmException fromHttpError(int statusCode, String body, Duration retryAfter) {
        JsonNode error = readQuietly(body == null ? "" : body).path("error");
        String detail = error.isObject() ? describe(error) : oneLine(body == null ? "" : body);
        return new LlmException(statusKind(statusCode, error),
                "OpenAI API 오류 (HTTP " + statusCode + ")" + (detail.isEmpty() ? "" : ": " + detail), null, retryAfter);
    }

    static Kind statusKind(int statusCode, JsonNode error) {
        // 403은 지원하지 않는 국가·지역이다. 키는 맞아도 다른 문서도 같은 이유로 실패하므로 키·권한 오류로 본다
        if (statusCode == 401 || statusCode == 403) {
            return Kind.AUTH;
        }
        // 부르는 경로는 /v1/responses 하나라 404는 모르는 모델이거나 주소(프록시 경로)가 틀린 것이다. 다른 문서를 불러도 똑같이 실패한다
        if (statusCode == 404) {
            return Kind.SETTINGS;
        }
        // OpenAI는 잔액 부족을 429로 알리지만 설정의 baseUrl로 거치는 게이트웨이는 402로 알리기도 한다. 기다려도 풀리지 않는다
        if (statusCode == 402) {
            return Kind.QUOTA;
        }
        if (statusCode == 429) {
            if (isQuota(error)) {
                return Kind.QUOTA;
            }
            // 요청 하나가 분당 토큰 한도(입력과 출력 상한의 합)를 넘었다("Request too large for … tokens per min"). 기다려도 같은 요청은
            // 늘 넘으므로 다시 부르지 않는다. 작은 문서는 통과할 수 있어 실행을 멈추지 않고 그 문서만 실패로 센다
            return error.path("message").asText("").startsWith("Request too large") ? Kind.BAD_REQUEST : Kind.RATE_LIMITED;
        }
        if (statusCode == 400) {
            String param = error.path("param").asText("");
            // 스트리밍을 쓰려면 조직 인증이 필요한 모델이 있다. 키는 맞아도 권한 문제라 모든 문서가 같이 실패한다
            if ("stream".equals(param)) {
                return Kind.AUTH;
            }
            if (isSettingsParam(param)) {
                return Kind.SETTINGS;
            }
        }
        // 408·409는 공식 SDK도 다시 부른다. 5xx와 과부하(503)도 다시 부른다
        if (statusCode == 408 || statusCode == 409 || statusCode >= 500) {
            return Kind.UNAVAILABLE;
        }
        return Kind.BAD_REQUEST;
    }

    /**
     * 문서와 상관없이 모델·계정 설정으로 정해지는 요청 필드. 그 모델이 받지 않는 추론 깊이({@code gpt-6.1-sol}의 none 등), 프로젝트가
     * 막은 처리 등급, 명시 캐시를 받지 않는 이전 모델, 출력 한도가 더 작은 모델 같은 400은 문서마다 똑같이 되풀이되므로 설정 오류로 보고
     * 실행을 멈추게 한다. 스키마({@code text.format})처럼 우리가 만든 값의 오류는 요청 오류로 둔다
     */
    private static boolean isSettingsParam(String param) {
        return param.startsWith("reasoning") || param.equals("model") || param.equals("service_tier")
                || param.equals("max_output_tokens") || param.contains("prompt_cache");
    }

    private static boolean isQuota(JsonNode error) {
        return QUOTA_CODES.contains(error.path("code").asText("")) || "insufficient_quota".equals(error.path("type").asText(""));
    }

    /** 마지막 이벤트의 응답 객체로 결과를 만든다 */
    private static LlmResponse response(JsonNode response, StringBuilder streamed, boolean refused) {
        String text = streamed.length() > 0 ? streamed.toString() : outputText(response);
        Finish finish = finish(response, refused || hasRefusal(response));
        return new LlmResponse(text, response.path("model").asText(""), usage(response.path("usage")), finish);
    }

    /**
     * 마지막 이벤트의 응답 객체를 끝난 방식으로 바꾼다. 잘림·거절이 아닌 미완료 이유와 모르는 상태는 {@link Finish#COMPLETE}로 넘겨
     * 출력 JSON으로 판단하게 한다. 출력은 스키마로 강제한 JSON이라 끝까지 적혀야만 읽히므로, 읽히면 다 된 출력이고, 읽히지 않으면
     * llm 엔진이 JSON을 읽지 못한 응답처럼 한 번 더 부른 뒤 실패로 센다. 응답을 끝까지 받아 이미 과금됐으므로 예외로 알려 토큰을
     * 보고에서 빼거나, 다시 부를 실패로 알려 재시도마다 과금되게 하지 않는다.
     */
    static Finish finish(JsonNode response, boolean refused) {
        if (refused) {
            return Finish.REFUSED;
        }
        if ("incomplete".equals(response.path("status").asText(""))) {
            String reason = response.path("incomplete_details").path("reason").asText("");
            if ("max_output_tokens".equals(reason)) {
                return Finish.TRUNCATED;
            }
            if ("content_filter".equals(reason)) {
                return Finish.REFUSED;
            }
        }
        return Finish.COMPLETE;
    }

    /** 사용량이 null로 오는 응답(실패·미완료)도 있어 그때는 0이다 */
    static Usage usage(JsonNode usage) {
        long input = usage.path("input_tokens").asLong();
        long cacheRead = usage.path("input_tokens_details").path("cached_tokens").asLong();
        long cacheWrite = usage.path("input_tokens_details").path("cache_write_tokens").asLong();
        return new Usage(Math.max(0, input - cacheRead - cacheWrite), cacheRead, cacheWrite,
                // 추론 토큰이 들어 있다
                usage.path("output_tokens").asLong(),
                usage.path("output_tokens_details").path("reasoning_tokens").asLong());
    }

    /** 글 조각 이벤트를 받지 못했을 때 마지막 응답의 {@code output[]}에서 글을 모은다 */
    private static String outputText(JsonNode response) {
        StringBuilder text = new StringBuilder();
        for (JsonNode item : response.path("output")) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText(""));
                }
            }
        }
        return text.toString();
    }

    private static boolean hasRefusal(JsonNode response) {
        for (JsonNode item : response.path("output")) {
            for (JsonNode part : item.path("content")) {
                if ("refusal".equals(part.path("type").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode parse(SseReader.Event event) throws LlmException {
        try {
            JsonNode data = MAPPER.readTree(event.data());
            return data == null ? MissingNode.getInstance() : data;
        } catch (JsonProcessingException e) {
            // 서버나 중간 프록시가 스트림을 망가뜨린 경우다. 회사는 끝까지 생성해 과금할 수 있어 끊긴 스트림처럼 다시 부르지 않는다
            throw new LlmException(Kind.INTERRUPTED, "OpenAI API 응답 스트림의 " + event.name() + " 이벤트를 해석할 수 없습니다", e);
        }
    }

    /**
     * HTTP 200으로 시작한 스트림 중간의 오류는 상태 코드가 없어 오류 코드로 가른다. 데이터 보관 지역이 프로젝트 설정과 맞지 않는 것은
     * 모든 문서가 같이 실패하므로 설정 오류다. 서버 오류와 모르는 코드는 회사가 스스로 생성을 멈춘 것이라 다시 부른다
     */
    private static LlmException streamError(JsonNode error) {
        // 코드가 null이면 종류({@code invalid_request_error} 등)로 가른다
        String code = error.path("code").asText("");
        if (code.isEmpty()) {
            code = error.path("type").asText("");
        }
        Kind kind = switch (code) {
            case "rate_limit_exceeded" -> Kind.RATE_LIMITED;
            case "invalid_prompt", "invalid_request_error" -> Kind.BAD_REQUEST;
            case "data_residency_mismatch" -> Kind.SETTINGS;
            default -> isQuota(error) ? Kind.QUOTA : Kind.UNAVAILABLE;
        };
        return new LlmException(kind, "OpenAI API 응답 스트림 오류 (" + code + "): " + error.path("message").asText(""));
    }

    private static String describe(JsonNode error) {
        String code = error.path("code").asText("");
        String type = error.path("type").asText("");
        String kind = !code.isEmpty() ? code : type;
        return (kind.isEmpty() ? "" : kind + ": ") + error.path("message").asText("");
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
