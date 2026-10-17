package kr.go.h2spec.parser.llm.openai;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmSettings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** OpenAI(GPT) 어댑터의 팩토리. {@link kr.go.h2spec.parser.llm.client.LlmProviders#ALL}에 적혀 있다. */
public final class OpenAiProviderFactory implements LlmProviderFactory {

    @Override
    public String id() {
        return "openai";
    }

    /**
     * 기본 모델. OpenAI 문서(2026-10)가 플래그십에 가까운 품질을 5분의 1 값에 낸다고 소개하는 모델이고, 값이 Claude Sonnet 5.5와 같다
     * (입력 $2 / 출력 $10, 100만 토큰당). 출력 한도가 12만 8천이라 llm 엔진의 기본 출력 상한과 맞는다
     */
    @Override
    public String defaultModel() {
        return "gpt-6.1-sol";
    }

    /** {@code medium}. OpenAI의 기본값과 같지만 회사 기본값이 바뀌어도 결과가 조용히 달라지지 않게 늘 적어 보낸다 */
    @Override
    public String defaultEffort() {
        return "medium";
    }

    /**
     * {@code reasoning.effort}의 값(2026-10 OpenAI API 명세). 모델마다 받는 범위가 달라서({@code gpt-6.1-sol}은 none·minimal을
     * 받지 않는다) 그 모델이 받지 않는 값은 회사가 400으로 거절하고, CLI가 그 이유를 알리고 실행을 멈춘다
     */
    @Override
    public List<String> effortLevels() {
        return List.of("none", "minimal", "low", "medium", "high", "xhigh", "max");
    }

    /**
     * 30분. 추론하는 동안에는 스트림에 아무것도 보내지 않을 수 있어 Claude(10분)보다 길게 잡는다
     */
    @Override
    public Duration defaultRequestTimeout() {
        return Duration.ofMinutes(30);
    }

    @Override
    public List<String> apiKeyEnvironmentVariables() {
        return List.of("OPENAI_API_KEY");
    }

    /** {@code gpt-}, {@code chatgpt-}, {@code o3}처럼 o와 숫자, 미세 조정 모델 {@code ft:gpt-}로 시작하는 이름 (대소문자 무시) */
    @Override
    public boolean recognizesModel(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        return name.startsWith("gpt-") || name.startsWith("chatgpt-") || name.startsWith("ft:gpt-") || name.matches("o\\d.*");
    }

    @Override
    public LlmClient create(LlmSettings settings) {
        return new OpenAiClient(settings);
    }
}
