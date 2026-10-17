package kr.go.h2spec.parser.llm.claude;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmSettings;

import java.util.List;
import java.util.Locale;

/** Claude(Anthropic) 어댑터의 팩토리. {@link kr.go.h2spec.parser.llm.client.LlmProviders#ALL}에 적혀 있다. */
public final class ClaudeProviderFactory implements LlmProviderFactory {

    @Override
    public String id() {
        return "claude";
    }

    /**
     * 기본 모델. 문서 16개를 프롬프트 v4로 실제 호출해 보니 Sonnet 5.5가 Opus 5.5와 같은 수준의 지표(오퍼레이션 재현율·정밀도
     * 100%, 틀린 URL 0)를 절반 이하 비용과 두 배 속도로 냈다. 오퍼레이션이 검증에서 건너뛰어지는 문서는 {@code --model claude-opus-5-5}로
     * 다시 돌려 볼 수 있다. 문서를 더 모아 평가한 뒤 확정한다
     */
    @Override
    public String defaultModel() {
        return "claude-sonnet-5-5";
    }

    /**
     * {@code medium}. 실제 호출로 시험한 값이다. Sonnet 5.5의 API 기본값은 {@code high}지만, 회사 기본값이 바뀌어도 결과가 조용히 달라지지
     * 않게 늘 적어 보낸다. 다른 깊이는 아직 재 보지 않았다
     */
    @Override
    public String defaultEffort() {
        return "medium";
    }

    /**
     * {@code output_config.effort}의 값. Opus 5.5와 Sonnet 5.5는 다섯 값을 모두 받는다(2026-10 Claude 문서). 그보다 앞선 모델 가운데
     * {@code xhigh}나 {@code max}를 받지 않는 모델은 회사가 400으로 거절하고, CLI가 그 이유를 알리고 실행을 멈춘다
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

    /** {@code claude-}로 시작하는 이름 (대소문자 무시) */
    @Override
    public boolean recognizesModel(String model) {
        return model.toLowerCase(Locale.ROOT).startsWith("claude-");
    }

    @Override
    public LlmClient create(LlmSettings settings) {
        return new ClaudeClient(settings);
    }
}
