package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmSettings;

import java.util.List;

/** Claude(Anthropic) 어댑터의 팩토리 (설계 6.2). {@link kr.go.h2spec.parser.llm.client.LlmProviders#ALL}에 적혀 있다. */
public final class ClaudeProviderFactory implements LlmProviderFactory {

    @Override
    public String id() {
        return "claude";
    }

    /** 설계 6.2의 기본 모델. 1-10 평가에서 확정한다 */
    @Override
    public String defaultModel() {
        return "claude-opus-5-5";
    }

    /** Opus 5.5의 API 기본값과 같은 {@code medium}. 1-10 평가에서 확정한다 */
    @Override
    public String defaultEffort() {
        return "medium";
    }

    @Override
    public List<String> apiKeyEnvironmentVariables() {
        return List.of("ANTHROPIC_API_KEY");
    }

    @Override
    public LlmClient create(LlmSettings settings) {
        return new ClaudeClient(settings);
    }
}
