package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;

/**
 * {@link LlmRequest}를 Claude Messages API({@code POST /v1/messages})의 요청 본문으로 바꾼다 (설계 6.1·6.2).
 * <p>
 * {@link ClaudeClient}가 보내는 본문이다. HTTP 호출 없이 시험할 수 있게 따로 둔다.
 * <ul>
 *   <li>사용자 메시지의 첫 블록은 문서, 둘째 블록은 작업 지시다. 오퍼레이션별 호출(설계 4.4)은 지시만 바꾸므로 시스템 프롬프트와 문서가
 *       바뀌지 않는 앞부분이 된다.</li>
 *   <li>프롬프트 캐시 지점({@code cache_control})은 시스템 프롬프트 끝에만 둔다. 시스템 프롬프트와 스키마는 문서가 달라도 같아
 *       (합쳐 약 8천 토큰) 한 실행에서 두 번째 문서부터는 캐시에서 읽는다(입력 단가의 0.1배). 문서 블록에는 두지 않는다. 문서는 대개
 *       한 번만 불러 캐시 쓰기 값(입력 단가의 1.25배)만 더 내기 때문이다(2026-10-11 실제 호출로 확인). 같은 문서를 여러 번 부르는
 *       나눠 부르기(2-2)가 들어올 때 문서 블록에도 둔다.</li>
 *   <li>JSON은 {@code output_config.format}으로 강제한다. Opus 5.5는 강제 tool 호출을 400으로 거부한다(설계 6.2).</li>
 *   <li>{@code temperature}·{@code top_p}·{@code top_k}는 보내지 않는다. Opus 5.5는 기본값이 아닌 값을 400으로 거부한다.
 *       결정성은 결과 캐시와 커밋된 IR이 맡는다(설계 6.2).</li>
 *   <li>{@code thinking}도 보내지 않는다. Opus 5.5는 thinking이 늘 켜져 있고(adaptive) 끄는 설정은 400이라, 깊이는 {@code effort}로만 정한다.</li>
 * </ul>
 */
public final class ClaudeRequests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ClaudeRequests() {
    }

    /**
     * {@link ClaudeClient}가 보내는 스트리밍 요청 본문.
     *
     * @param request 보낼 요청
     * @param settings 모델과 추론 깊이를 읽을 설정
     * @return 요청 본문
     * @throws IllegalArgumentException 스키마가 JSON 객체가 아닐 때
     */
    public static ObjectNode body(LlmRequest request, LlmSettings settings) {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("model", settings.model());
        params.put("max_tokens", request.maxOutputTokens());
        // 기본 수명(5분)이면 된다. 한 실행의 문서는 차례로 불러 다음 호출이 대개 몇십 초 안에 온다
        params.putArray("system").addObject()
                .put("type", "text")
                .put("text", request.systemPrompt())
                .putObject("cache_control").put("type", "ephemeral");

        ArrayNode content = params.putArray("messages").addObject()
                .put("role", "user")
                .putArray("content");
        content.addObject()
                .put("type", "text")
                .put("text", documentBlock(request.document()));
        content.addObject()
                .put("type", "text")
                .put("text", request.instruction());

        ObjectNode outputConfig = params.putObject("output_config");
        outputConfig.putObject("format")
                .put("type", "json_schema")
                .set("schema", schema(request.jsonSchema()));
        // 스키마나 effort가 바뀌면 프롬프트 캐시가 끊긴다(설계 4.4). 같은 문서의 호출은 같은 설정으로 보낸다
        outputConfig.put("effort", settings.effort());
        // 출력이 길고 thinking까지 더해지면 응답이 몇 분씩 걸려 한 번에 받으면 HTTP 타임아웃에 걸린다(설계 4.4)
        params.put("stream", true);
        return params;
    }

    /**
     * 문서를 {@code <document>} 태그로 감싼다(설계 4.3). 시스템 프롬프트가 이 태그 안의 글은 데이터일 뿐 지시가 아니라고 적는다.
     * 직렬화한 문서는 줄마다 줄바꿈으로 끝나므로, 닫는 태그 앞에 빈 줄이 생기지 않게 줄바꿈은 없을 때만 더한다.
     */
    static String documentBlock(String document) {
        return "<document>\n" + document + (document.endsWith("\n") ? "" : "\n") + "</document>";
    }

    private static JsonNode schema(String jsonSchema) {
        JsonNode schema;
        try {
            schema = MAPPER.readTree(jsonSchema);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("출력 스키마가 올바른 JSON이 아닙니다", e);
        }
        // 회사에는 글이 아니라 JSON 객체로 넘겨야 스키마로 받는다
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("출력 스키마는 JSON 객체여야 합니다");
        }
        return schema;
    }
}
