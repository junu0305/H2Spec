package kr.go.h2spec.parser.llm.client;

import kr.go.h2spec.parser.llm.claude.ClaudeClient;
import kr.go.h2spec.parser.llm.claude.ClaudeProviderFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProvidersTest {

    @Test
    void 일단계_목록에는_Claude만_있다() {
        assertEquals(1, LlmProviders.ALL.size());
        assertInstanceOf(ClaudeProviderFactory.class, LlmProviders.ALL.get(0));
        assertEquals(List.of("claude"), LlmProviders.ids());
    }

    @Test
    void ID로_찾고_앞뒤_공백과_대소문자는_가리지_않는다() {
        assertEquals(Optional.of(LlmProviders.ALL.get(0)), LlmProviders.find("claude"));
        assertEquals(Optional.of(LlmProviders.ALL.get(0)), LlmProviders.find(" CLAUDE "));
        assertTrue(LlmProviders.find("openai").isEmpty());
        assertTrue(LlmProviders.find(null).isEmpty());
    }

    @Test
    void Claude_팩토리는_설계의_기본값과_키_환경변수를_알린다() {
        LlmProviderFactory claude = new ClaudeProviderFactory();

        assertEquals("claude", claude.id());
        assertEquals("claude-opus-5-5", claude.defaultModel());
        assertEquals("medium", claude.defaultEffort());
        assertEquals(List.of("ANTHROPIC_API_KEY"), claude.apiKeyEnvironmentVariables());
        assertInstanceOf(ClaudeClient.class, claude.create(LlmSettings.of(claude.defaultModel(), claude.defaultEffort(), "sk-ant-test")));
    }
}
