package kr.go.h2spec.parser.llm.gemini;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeminiRequestsTest {

    private static final ExtractionPrompt PROMPT = ExtractionPrompt.load();

    @Test
    void 시스템_지시와_두_글_조각과_JSON_스키마와_생각_수준을_보내고_출력_상한은_모델_한도에서_자른다() throws Exception {
        LlmRequest request = new LlmRequest(PROMPT.promptText(), "[P1] 문서\n", "지시", PROMPT.schemaText(), 128_000);

        JsonNode body = GeminiRequests.body(request, LlmSettings.of("gemini-3.8-flash", "medium", "AIza-test"));

        assertEquals(List.of("systemInstruction", "contents", "generationConfig"), names(body));
        assertEquals(PROMPT.promptText(), body.path("systemInstruction").path("parts").path(0).path("text").asText());
        JsonNode parts = body.path("contents").path(0).path("parts");
        assertEquals("user", body.path("contents").path(0).path("role").asText());
        assertEquals("<document>\n[P1] 문서\n</document>", parts.path(0).path("text").asText());
        assertEquals("지시", parts.path(1).path("text").asText());
        JsonNode generation = body.path("generationConfig");
        assertEquals(List.of("responseMimeType", "responseJsonSchema", "maxOutputTokens", "thinkingConfig"), names(generation),
                "temperature·topP·topK는 보내지 않는다");
        assertEquals("application/json", generation.path("responseMimeType").asText());
        assertEquals(new ObjectMapper().readTree(PROMPT.schemaText()), generation.path("responseJsonSchema"));
        assertEquals(65_536, generation.path("maxOutputTokens").asInt(), "Gemini의 출력 한도를 넘겨 보내지 않는다");
        assertEquals("MEDIUM", generation.path("thinkingConfig").path("thinkingLevel").asText());
    }

    @Test
    void 모델_ID에_주소_경로를_바꿀_글자가_있으면_만들_때_거절한다() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeminiClient(LlmSettings.of("../files/x", "medium", "AIza-test")));
        assertThrows(IllegalArgumentException.class,
                () -> new GeminiClient(LlmSettings.of("gemini-3.8-flash?alt=json", "medium", "AIza-test")));
        assertThrows(IllegalArgumentException.class,
                () -> new GeminiClient(LlmSettings.of("models/../x", "medium", "AIza-test")));
    }

    @Test
    void 모델_목록의_models_접두사가_붙은_이름도_같은_주소로_부른다() {
        String expected = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:streamGenerateContent?alt=sse";

        assertEquals(expected, GeminiClient.streamUri(LlmSettings.of("gemini-3.8-flash", "medium", "AIza-test")).toString());
        assertEquals(expected, GeminiClient.streamUri(LlmSettings.of("models/gemini-3.8-flash", "medium", "AIza-test")).toString());
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
