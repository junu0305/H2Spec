package kr.go.h2spec.parser.llm.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;

import java.util.Locale;

/**
 * {@link LlmRequest}를 Gemini API({@code streamGenerateContent})의 요청 본문으로 바꾼다.
 * <ul>
 *   <li>시스템 프롬프트는 {@code systemInstruction}으로, 문서와 작업 지시는 user 턴의 두 글 조각으로 보낸다. 바뀌지 않는 앞부분을
 *       앞에 두어야 암묵적 캐시가 걸린다. Gemini는 캐시 지점을 따로 두지 않고 같은 앞부분을 알아서 캐시한다.</li>
 *   <li>JSON은 {@code responseMimeType}과 {@code responseJsonSchema}(표준 JSON Schema)로 강제한다. OpenAPI 부분집합인
 *       {@code responseSchema}는 우리 스키마의 {@code additionalProperties}를 받지 않는다.</li>
 *   <li>출력 상한({@code maxOutputTokens})은 생각 토큰을 포함하고 {@value #MAX_OUTPUT_TOKENS}에서 자른다. 스트리밍 호출 한 번의 출력
 *       한도다({@code gemini-4-argon}은 이보다 크게 주면 긴 출력 이어 쓰기가 켜지는데 llm 엔진은 그것을 쓰지 않는다). 잘리면 나눠 부르기가
 *       이어 간다.</li>
 *   <li>{@code temperature}·{@code topP}·{@code topK}는 보내지 않는다. 최근 모델은 무시하고 앞으로는 400으로 거절한다고 문서가 적는다.</li>
 * </ul>
 */
public final class GeminiRequests {

    /** 스트리밍 호출 한 번의 출력 한도. 스트리밍이 아닌 호출은 모델에 따라 더 작다({@code gemini-4-argon}은 32,768) */
    static final int MAX_OUTPUT_TOKENS = 65_536;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GeminiRequests() {
    }

    /**
     * {@link GeminiClient}가 보내는 스트리밍 요청 본문.
     *
     * @param request  보낼 요청
     * @param settings 추론 깊이를 읽을 설정. 모델은 주소에 들어간다
     * @return 요청 본문
     * @throws IllegalArgumentException 스키마가 JSON 객체가 아닐 때
     */
    public static ObjectNode body(LlmRequest request, LlmSettings settings) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("systemInstruction").putArray("parts").addObject().put("text", request.systemPrompt());
        ArrayNode parts = body.putArray("contents").addObject()
                .put("role", "user")
                .putArray("parts");
        parts.addObject().put("text", request.wrappedDocument());
        parts.addObject().put("text", request.instruction());

        ObjectNode generation = body.putObject("generationConfig");
        generation.put("responseMimeType", "application/json");
        generation.set("responseJsonSchema", request.schemaObject());
        generation.put("maxOutputTokens", Math.min(request.maxOutputTokens(), MAX_OUTPUT_TOKENS));
        generation.putObject("thinkingConfig").put("thinkingLevel", settings.effort().toUpperCase(Locale.ROOT));
        return body;
    }
}
