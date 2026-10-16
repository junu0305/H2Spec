package kr.go.h2spec.parser.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.go.h2spec.parser.llm.ExtractionPrompt;
import kr.go.h2spec.parser.llm.client.LlmRequest;
import kr.go.h2spec.parser.llm.client.LlmSettings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiRequestsTest {

    private static final ExtractionPrompt PROMPT = ExtractionPrompt.load();
    private final LlmSettings settings = LlmSettings.of("gpt-6.1-sol", "medium", "sk-proj-test");

    @Test
    void 시스템_프롬프트는_developer_메시지로_문서와_지시는_user_메시지로_보내고_시스템_프롬프트에만_캐시_지점을_둔다() throws Exception {
        LlmRequest request = new LlmRequest(PROMPT.promptText(), "[P1] 문서\n", "지시", PROMPT.schemaText(), 32000);

        JsonNode body = OpenAiRequests.body(request, settings);

        assertEquals(List.of("model", "input", "text", "reasoning", "max_output_tokens", "prompt_cache_options", "store",
                "stream"), names(body), "temperature·top_p는 보내지 않는다");
        assertEquals("gpt-6.1-sol", body.path("model").asText());
        JsonNode system = body.path("input").path(0);
        assertEquals("developer", system.path("role").asText());
        assertEquals(PROMPT.promptText(), system.path("content").path(0).path("text").asText());
        assertEquals("explicit", system.path("content").path(0).path("prompt_cache_breakpoint").path("mode").asText());
        JsonNode user = body.path("input").path(1).path("content");
        assertEquals("user", body.path("input").path(1).path("role").asText());
        assertEquals("<document>\n[P1] 문서\n</document>", user.path(0).path("text").asText());
        assertFalse(user.path(0).has("prompt_cache_breakpoint"), "문서를 한 번 부를 때는 캐시에 쓰지 않는다");
        assertEquals("지시", user.path(1).path("text").asText());
        JsonNode format = body.path("text").path("format");
        assertEquals("json_schema", format.path("type").asText());
        assertTrue(format.path("strict").booleanValue(), "엄격 모드는 기본이 꺼져 있어 적어 보낸다");
        assertEquals(new ObjectMapper().readTree(PROMPT.schemaText()), format.path("schema"));
        assertEquals("medium", body.path("reasoning").path("effort").asText());
        assertEquals(32000, body.path("max_output_tokens").asInt());
        assertEquals("explicit", body.path("prompt_cache_options").path("mode").asText());
        assertFalse(body.path("store").booleanValue(), "문서에서 뽑은 내용을 OpenAI에 남기지 않는다");
        assertTrue(body.path("stream").booleanValue());

        JsonNode split = OpenAiRequests.body(new LlmRequest(PROMPT.promptText(), "[P1] 문서\n", "지시", PROMPT.schemaText(),
                32000, true), settings);
        assertEquals("explicit",
                split.path("input").path(1).path("content").path(0).path("prompt_cache_breakpoint").path("mode").asText(),
                "나눠 부를 때는 문서도 캐시에 올린다");
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
