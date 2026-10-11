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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link LlmRequest}를 Claude Messages API 요청 본문으로 바꾸는 규칙(설계 6.2)을 본다. */
class ClaudeRequestsTest {

    private static final ExtractionPrompt PROMPT = ExtractionPrompt.load();
    private static final String DOCUMENT = "[P1] 요청 URL\n[P2] http://apis.data.go.kr/B552584/MsrstnInfoInqireSvc/getMsrstnList\n";
    private static final String INSTRUCTION = "문서의 모든 오퍼레이션을 문서에 나온 순서대로 뽑아라.";

    private final LlmSettings settings = LlmSettings.of("claude-opus-5-5", "medium", "sk-ant-api03-test");
    private final LlmRequest request = new LlmRequest(PROMPT.promptText(), DOCUMENT, INSTRUCTION, PROMPT.schemaText(), 32000);

    @Test
    void 모델과_출력_한도와_시스템_프롬프트를_적는다() {
        ObjectNode body = ClaudeRequests.body(request, settings);

        assertEquals("claude-opus-5-5", body.path("model").asText());
        assertEquals(32000, body.path("max_tokens").asInt());
        assertEquals(PROMPT.promptText(), body.path("system").asText());
        assertTrue(body.path("stream").booleanValue());
    }

    @Test
    void 문서_블록에_캐시를_걸고_작업_지시_블록을_그_뒤에_둔다() {
        JsonNode messages = ClaudeRequests.body(request, settings).path("messages");

        assertEquals(1, messages.size());
        assertEquals("user", messages.path(0).path("role").asText());
        JsonNode content = messages.path(0).path("content");
        assertEquals(2, content.size());
        // 시스템 프롬프트와 문서가 캐시되는 앞부분이다. 캐시 지점은 문서 블록 하나에만 둔다
        assertEquals("text", content.path(0).path("type").asText());
        assertEquals("<document>\n" + DOCUMENT + "</document>", content.path(0).path("text").asText());
        assertEquals(json("{\"type\":\"ephemeral\"}"), content.path(0).path("cache_control"));
        assertEquals("text", content.path(1).path("type").asText());
        assertEquals(INSTRUCTION, content.path(1).path("text").asText());
        assertEquals(List.of("type", "text"), names(content.path(1)));
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
        // Opus 5.5는 기본값이 아닌 temperature·top_p·top_k와 thinking 끄기를 400으로 거부한다. 정한 필드 말고는 아무것도 보내지 않는다
        assertEquals(List.of("model", "max_tokens", "system", "messages", "output_config", "stream"),
                names(ClaudeRequests.body(request, settings)));
    }

    @Test
    void 배치용_파라미터는_스트리밍_본문에서_stream만_뺀_것이다() {
        ObjectNode body = ClaudeRequests.body(request, settings);
        body.remove("stream");

        assertEquals(body, ClaudeRequests.messageParams(request, settings));
    }

    @Test
    void 줄바꿈으로_끝나지_않은_문서도_닫는_태그를_다음_줄에_둔다() {
        assertEquals("<document>\n[P1] 제목\n</document>", ClaudeRequests.documentBlock("[P1] 제목"));
        assertEquals("<document>\n[P1] 제목\n</document>", ClaudeRequests.documentBlock("[P1] 제목\n"));
        assertEquals("<document>\n\n</document>", ClaudeRequests.documentBlock(""));
    }

    @Test
    void 스키마가_JSON_객체가_아니면_보내기_전에_알린다() {
        for (String schema : List.of("{\"type\":", "[\"object\"]", "")) {
            LlmRequest bad = new LlmRequest(PROMPT.promptText(), DOCUMENT, INSTRUCTION, schema, 32000);

            assertThrows(IllegalArgumentException.class, () -> ClaudeRequests.body(bad, settings), schema);
        }
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
