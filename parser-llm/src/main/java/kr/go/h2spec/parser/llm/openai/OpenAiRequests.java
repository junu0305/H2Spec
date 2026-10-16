package kr.go.h2spec.parser.llm.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;

/**
 * {@link LlmRequest}를 OpenAI Responses API({@code POST /v1/responses})의 요청 본문으로 바꾼다.
 * <ul>
 *   <li>시스템 프롬프트는 맨 앞의 developer 메시지로, 문서와 작업 지시는 user 메시지의 두 글 조각으로 보낸다. 최상위
 *       {@code instructions}에는 프롬프트 캐시 지점을 둘 수 없기 때문이다.</li>
 *   <li>프롬프트 캐시는 명시 모드({@code prompt_cache_options.mode: explicit})로 쓰고 지점은 시스템 프롬프트 끝에 둔다. 문서 조각에는
 *       {@link LlmRequest#cacheDocument()}일 때만 둔다. OpenAI는 캐시 쓰기를 입력 단가의 1.25배로 받고, 지점이 없는 글은 쓰지 않는다.
 *       Claude 어댑터와 같은 까닭이다.</li>
 *   <li>JSON은 {@code text.format}(json_schema, strict)으로 강제한다. 엄격 모드는 기본이 꺼져 있어 적어 보낸다.</li>
 *   <li>{@code store}는 false다. 기본값은 true라 응답이 OpenAI에 30일 이상 남는다. 문서에서 뽑은 내용이라 남기지 않는다.</li>
 *   <li>{@code temperature}·{@code top_p}는 보내지 않는다. 추론 깊이가 none이 아니면 보내지 말라고 문서가 적는다.
 *       결정성은 결과 캐시와 커밋된 IR이 맡는다.</li>
 * </ul>
 */
public final class OpenAiRequests {

    /** {@code text.format.name}. 영문자·숫자·{@code _}·{@code -}만 받는다 */
    static final String SCHEMA_NAME = "api_extraction";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OpenAiRequests() {
    }

    /**
     * {@link OpenAiClient}가 보내는 스트리밍 요청 본문.
     *
     * @param request  보낼 요청
     * @param settings 모델과 추론 깊이를 읽을 설정
     * @return 요청 본문
     * @throws IllegalArgumentException 스키마가 JSON 객체가 아닐 때
     */
    public static ObjectNode body(LlmRequest request, LlmSettings settings) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", settings.model());
        ArrayNode input = body.putArray("input");
        ObjectNode system = message(input, "developer");
        text(system, request.systemPrompt(), true);
        ObjectNode user = message(input, "user");
        text(user, request.wrappedDocument(), request.cacheDocument());
        text(user, request.instruction(), false);

        body.putObject("text").putObject("format")
                .put("type", "json_schema")
                .put("name", SCHEMA_NAME)
                .put("strict", true)
                .set("schema", request.schemaObject());
        body.putObject("reasoning").put("effort", settings.effort());
        body.put("max_output_tokens", request.maxOutputTokens());
        body.putObject("prompt_cache_options").put("mode", "explicit");
        body.put("store", false);
        // 출력이 길고 추론 토큰까지 더해지면 응답이 몇 분씩 걸려 한 번에 받으면 HTTP 타임아웃에 걸린다
        body.put("stream", true);
        return body;
    }

    private static ObjectNode message(ArrayNode input, String role) {
        ObjectNode message = input.addObject();
        message.put("type", "message");
        message.put("role", role);
        message.putArray("content");
        return message;
    }

    private static void text(ObjectNode message, String text, boolean cacheBreakpoint) {
        ObjectNode part = ((ArrayNode) message.get("content")).addObject()
                .put("type", "input_text")
                .put("text", text);
        if (cacheBreakpoint) {
            part.putObject("prompt_cache_breakpoint").put("mode", "explicit");
        }
    }
}
