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

    /**
     * 설계 6.2의 기본 모델. 2026-10-11 실제 호출에서 Sonnet 5.5가 Opus 5.5와 거의 같은 결과(URL·파라미터·응답 경로·형식·성공코드)를
     * 절반 이하 비용과 두 배 속도로 냈다. 표준과 다른 응답 구조는 Opus가 더 잘 읽어, 그런 문서는 {@code --model claude-opus-5-5}로 다시 돌린다.
     * 1-10 평가에서 확정한다
     */
    @Override
    public String defaultModel() {
        return "claude-sonnet-5-5";
    }

    /**
     * {@code medium}. 실제 호출로 시험한 값이다. Sonnet 5.5의 API 기본값은 {@code high}지만, 회사 기본값이 바뀌어도 결과가 조용히 달라지지
     * 않게 늘 적어 보낸다. 1-10 평가에서 확정한다
     */
    @Override
    public String defaultEffort() {
        return "medium";
    }

    /**
     * {@code output_config.effort}의 값. Opus 5.5와 Sonnet 5.5는 다섯 값을 모두 받는다(2026-10 Claude 문서). 그보다 앞선 모델 가운데
     * {@code xhigh}나 {@code max}를 받지 않는 모델은 회사가 400으로 거절하고, CLI가 그 이유를 알린다
     */
    @Override
    public List<String> effortLevels() {
        return List.of("low", "medium", "high", "xhigh", "max");
    }

    @Override
    public List<String> apiKeyEnvironmentVariables() {
        return List.of("ANTHROPIC_API_KEY");
    }

    /** 공식 SDK가 워크스페이스를 고르는 데 쓰는 이름과 같다 */
    @Override
    public List<String> workspaceEnvironmentVariables() {
        return List.of("ANTHROPIC_WORKSPACE_ID");
    }

    @Override
    public LlmClient create(LlmSettings settings) {
        return new ClaudeClient(settings);
    }
}
