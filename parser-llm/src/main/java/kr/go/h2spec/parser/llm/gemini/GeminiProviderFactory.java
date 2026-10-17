package kr.go.h2spec.parser.llm.gemini;

import kr.go.h2spec.parser.llm.client.LlmClient;
import kr.go.h2spec.parser.llm.client.LlmProviderFactory;
import kr.go.h2spec.parser.llm.client.LlmSettings;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

/** Google Gemini 어댑터의 팩토리. {@link kr.go.h2spec.parser.llm.client.LlmProviders#ALL}에 적혀 있다. */
public final class GeminiProviderFactory implements LlmProviderFactory {

    @Override
    public String id() {
        return "gemini";
    }

    /**
     * 기본 모델. 구조화 출력을 받고 컨텍스트가 100만 토큰이며, 값이 입력 $0.75 / 출력 $3.75(100만 토큰당, 2026-12-31까지. 그 뒤 두 배)라
     * 긴 문서 추출에 맞다고 보았다. 더 나은 모델({@code gemini-4-argon})은 {@code --model}로 고른다
     */
    @Override
    public String defaultModel() {
        return "gemini-3.8-flash";
    }

    /** {@code medium}. 기본 모델의 기본값과 같지만 회사 기본값이 바뀌어도 결과가 조용히 달라지지 않게 늘 적어 보낸다 */
    @Override
    public String defaultEffort() {
        return "medium";
    }

    /**
     * {@code thinkingConfig.thinkingLevel}의 값(2026-10 Gemini 문서). 보낼 때 대문자로 바꾼다. {@code minimal}은 기본 모델과
     * {@code gemini-4-argon}이 받지 않아 보내기 전에 막는다(거절 문구가 문서에 없어 400을 설정 오류로 가르는 데 기댈 수 없다).
     * 그 밖에 모델이 받지 않는 값은 회사가 400으로 거절하고 CLI가 그 이유를 알리고 실행을 멈춘다. 생각 예산({@code thinkingBudget})만
     * 받는 2.5 이전 모델은 지원하지 않는다
     */
    @Override
    public List<String> effortLevels() {
        return List.of("low", "medium", "high");
    }

    /**
     * 30분. 추론하는 동안에는 스트림에 아무것도 보내지 않을 수 있어 Claude(10분)보다 길게 잡는다. Gemini 서버는 스트림을 20분에
     * 닫으므로(문서는 읽기 대기를 20분으로 두라고 한다) 그보다 길게 잡아 서버가 먼저 끝내게 둔다. 서버가 닫아 끊긴 스트림은 다시 부르지 않는다
     */
    @Override
    public Duration defaultRequestTimeout() {
        return Duration.ofMinutes(30);
    }

    /** 스트리밍 호출 한 번의 출력 한도 */
    @Override
    public int maxOutputTokens() {
        return GeminiRequests.MAX_OUTPUT_TOKENS;
    }

    /**
     * 공식 SDK가 읽는 두 이름이다. SDK는 둘 다 있으면 {@code GOOGLE_API_KEY}를 쓰지만 여기서는 {@code GEMINI_API_KEY}를 먼저 쓴다.
     * {@code GOOGLE_API_KEY}는 다른 구글 서비스용 키로 이미 설정돼 있는 경우가 많고, Gemini에 묶지 않은 키는 Gemini가 거절하기 때문이다
     */
    @Override
    public List<String> apiKeyEnvironmentVariables() {
        return List.of("GEMINI_API_KEY", "GOOGLE_API_KEY");
    }

    /** {@code GOOGLE_API_KEY}만 있을 때는 Gemini를 고르지 않는다. 다른 구글 서비스용 키일 수 있다 */
    @Override
    public List<String> detectionEnvironmentVariables() {
        return List.of("GEMINI_API_KEY");
    }

    /** {@code gemini-}나 모델 목록이 주는 꼴 {@code models/gemini-}로 시작하는 이름 (대소문자 무시) */
    @Override
    public boolean recognizesModel(String model) {
        String name = model.toLowerCase(Locale.ROOT);
        return name.startsWith("gemini-") || name.startsWith("models/gemini-");
    }

    @Override
    public LlmClient create(LlmSettings settings) {
        return new GeminiClient(settings);
    }
}
