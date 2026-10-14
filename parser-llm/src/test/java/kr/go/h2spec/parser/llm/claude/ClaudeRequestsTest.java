package kr.go.h2spec.parser.llm.claude;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LlmRequest}를 Claude Messages API 요청 본문으로 바꾸는 규칙을 본다. */
class ClaudeRequestsTest {

    private static final ExtractionPrompt PROMPT = ExtractionPrompt.load();
    private static final String DOCUMENT = "[P1] 요청 URL\n[P2] http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList\n";
    private static final String INSTRUCTION = "문서의 모든 오퍼레이션을 문서에 나온 순서대로 뽑아라.";

    private final LlmSettings settings = LlmSettings.of("claude-opus-5-5", "medium", "sk-ant-api03-test");
    private final LlmRequest request = new LlmRequest(PROMPT.promptText(), DOCUMENT, INSTRUCTION, PROMPT.schemaText(), 32000);

    @Test
    void 모델과_출력_한도와_시스템_프롬프트를_적고_시스템_프롬프트_끝에_프롬프트_캐시_지점을_둔다() {
        ObjectNode body = ClaudeRequests.body(request, settings);

        assertEquals("claude-opus-5-5", body.path("model").asText());
        assertEquals(32000, body.path("max_tokens").asInt());
        // 시스템 프롬프트와 스키마는 문서가 달라도 같아 두 번째 문서부터 캐시에서 읽는다
        JsonNode system = body.path("system");
        assertEquals(1, system.size());
        assertEquals("text", system.path(0).path("type").asText());
        assertEquals(PROMPT.promptText(), system.path(0).path("text").asText());
        assertEquals("ephemeral", system.path(0).path("cache_control").path("type").asText());
        assertEquals(List.of("type", "text", "cache_control"), names(system.path(0)));
        assertTrue(body.path("stream").booleanValue());
    }

    @Test
    void 문서_블록_뒤에_작업_지시_블록을_두고_나눠_부를_때만_문서에_프롬프트_캐시를_건다() {
        JsonNode messages = ClaudeRequests.body(request, settings).path("messages");

        assertEquals(1, messages.size());
        assertEquals("user", messages.path(0).path("role").asText());
        JsonNode content = messages.path(0).path("content");
        assertEquals(2, content.size());
        // 문서는 대개 한 번만 불러 캐시를 다시 읽을 일이 드물고 캐시 쓰기 값만 더 내므로 캐시 지점을 두지 않는다
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("<document>\n" + DOCUMENT + "</document>", content.path(0).path("text").asText());
        assertEquals(List.of("type", "text"), names(content.path(0)));
        assertEquals("text", content.path(1).path("type").asText());
        assertEquals(INSTRUCTION, content.path(1).path("text").asText());
        assertEquals(List.of("type", "text"), names(content.path(1)));

        // 나눠 부르기는 같은 문서를 여러 번 보내므로 두 번째 호출부터 문서를 캐시에서 읽는다
        LlmRequest split = new LlmRequest(PROMPT.promptText(), DOCUMENT, INSTRUCTION, PROMPT.schemaText(), 32000, true);
        JsonNode splitContent = ClaudeRequests.body(split, settings).path("messages").path(0).path("content");
        assertEquals("ephemeral", splitContent.path(0).path("cache_control").path("type").asText());
        assertEquals(List.of("type", "text"), names(splitContent.path(1)), "작업 지시는 호출마다 달라 캐시하지 않는다");
    }

    @Test
    void 출력_형식은_스키마_파일을_JSON_객체로_넘기고_추론_깊이를_함께_적는다() {
        JsonNode outputConfig = ClaudeRequests.body(request, settings).path("output_config");

        assertEquals("json_schema", outputConfig.path("format").path("type").asText());
        assertEquals(json(PROMPT.schemaText()), outputConfig.path("format").path("schema"));
        assertEquals("medium", outputConfig.path("effort").asText());
        assertEquals("high", ClaudeRequests.body(request, LlmSettings.of("claude-opus-5-5", "high", "sk-ant-api03-test"))
                .path("output_config").path("effort").asText());
    }

    @Test
    void 샘플링_파라미터와_thinking은_보내지_않는다() {
        // Opus 5.5·Sonnet 5.5는 기본값이 아닌 temperature·top_p·top_k와 thinking 끄기를 400으로 거부한다. 정한 필드 말고는 아무것도 보내지 않는다
        assertEquals(List.of("model", "max_tokens", "system", "messages", "output_config", "stream"),
                names(ClaudeRequests.body(request, settings)));
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static JsonNode json(String text) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
