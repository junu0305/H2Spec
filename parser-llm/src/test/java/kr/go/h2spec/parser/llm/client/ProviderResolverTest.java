package kr.go.h2spec.parser.llm.client;

import kr.go.h2spec.parser.llm.client.ProviderResolver.Engine;
import kr.go.h2spec.parser.llm.client.ProviderResolver.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 회사 결정 순서(설계 7.2)를 규칙마다 본다. 1단계에는 Claude뿐이라, 키 개수 규칙은 아직 없는 회사를 흉내 낸 목록으로 본다.
 */
class ProviderResolverTest {

    private static final LlmProviderFactory CLAUDE = new FakeProvider("claude", List.of("ANTHROPIC_API_KEY"));
    private static final LlmProviderFactory OPENAI = new FakeProvider("openai", List.of("OPENAI_API_KEY"));
    private static final LlmProviderFactory GEMINI = new FakeProvider("gemini", List.of("GOOGLE_API_KEY", "GEMINI_API_KEY"));
    private static final List<LlmProviderFactory> PROVIDERS = List.of(CLAUDE, OPENAI, GEMINI);

    @ParameterizedTest
    @EnumSource(Engine.class)
    void provider_옵션이_전용_환경변수와_키보다_먼저다(Engine engine) {
        Map<String, String> env = env("H2SPEC_LLM_PROVIDER", "gemini",
                "ANTHROPIC_API_KEY", "a-key", "OPENAI_API_KEY", "o-key", "GOOGLE_API_KEY", "g-key");

        assertUses(OPENAI, "o-key", resolver(env).resolve(engine, "openai"));
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void 옵션이_없으면_H2SPEC_LLM_PROVIDER를_쓰고_키가_여럿이어도_오류가_아니다(Engine engine) {
        Map<String, String> env = env("H2SPEC_LLM_PROVIDER", "gemini", "ANTHROPIC_API_KEY", "a-key", "GEMINI_API_KEY", "g-key");

        assertUses(GEMINI, "g-key", resolver(env).resolve(engine, null));
    }

    @Test
    void llm은_키가_한_회사_것뿐이면_그_회사를_쓴다() {
        assertUses(OPENAI, "o-key", resolver(env("OPENAI_API_KEY", "o-key")).resolve(Engine.LLM, null));
    }

    @Test
    void Gemini의_두_키_이름은_한_회사로_세고_GOOGLE_API_KEY가_우선한다() {
        Map<String, String> both = env("GOOGLE_API_KEY", "google-key", "GEMINI_API_KEY", "gemini-key");

        assertUses(GEMINI, "google-key", resolver(both).resolve(Engine.LLM, null));
        assertUses(GEMINI, "gemini-key", resolver(env("GEMINI_API_KEY", "gemini-key")).resolve(Engine.LLM, null));
    }

    @Test
    void llm은_키가_여러_회사_것이면_정해진_문구로_알린다() {
        ProviderResolver resolver = resolver(env("ANTHROPIC_API_KEY", "a-key", "OPENAI_API_KEY", "o-key"));

        ProviderResolutionException e = assertThrows(ProviderResolutionException.class,
                () -> resolver.resolve(Engine.LLM, null));

        assertEquals("LLM 키가 여러 개입니다(claude, openai). --provider 또는 H2SPEC_LLM_PROVIDER로 지정하세요", e.getMessage());
    }

    @Test
    void llm은_키가_없으면_키_환경변수를_알려_주며_실패한다() {
        ProviderResolutionException e = assertThrows(ProviderResolutionException.class,
                () -> resolver(env()).resolve(Engine.LLM, null));

        assertEquals("LLM API 키가 없습니다. 쓸 회사의 키를 환경변수(ANTHROPIC_API_KEY, OPENAI_API_KEY, GOOGLE_API_KEY, "
                + "GEMINI_API_KEY)로 설정하세요", e.getMessage());
    }

    @Test
    void llm은_정한_회사의_키가_없으면_실패한다() {
        ProviderResolver resolver = resolver(env("OPENAI_API_KEY", "o-key", "H2SPEC_LLM_PROVIDER", "gemini"));

        assertEquals("claude의 API 키가 없습니다. 환경변수 ANTHROPIC_API_KEY를 설정하세요",
                assertThrows(ProviderResolutionException.class, () -> resolver.resolve(Engine.LLM, "claude")).getMessage());
        assertEquals("gemini의 API 키가 없습니다. 환경변수 GOOGLE_API_KEY 또는 GEMINI_API_KEY를 설정하세요",
                assertThrows(ProviderResolutionException.class, () -> resolver.resolve(Engine.LLM, null)).getMessage());
    }

    @Test
    void auto는_범용_키만으로는_회사를_고르지_않고_안내와_함께_rule로_간다() {
        // 키가 하나든 여럿이든 auto는 키를 보고 고르지 않는다. 여럿이어도 오류로 멈추지 않는다
        for (Map<String, String> env : List.of(env("ANTHROPIC_API_KEY", "a-key"),
                env("ANTHROPIC_API_KEY", "a-key", "OPENAI_API_KEY", "o-key"), env())) {
            Resolution resolution = resolver(env).resolve(Engine.AUTO, null);

            Resolution.UseRule rule = assertInstanceOf(Resolution.UseRule.class, resolution, env.toString());
            assertEquals("LLM 회사가 지정되지 않아 rule 엔진으로 변환합니다. llm 엔진을 쓰려면 --provider 또는 "
                    + "H2SPEC_LLM_PROVIDER로 회사를 지정하세요", rule.notice());
        }
    }

    @Test
    void auto는_정한_회사의_키가_없으면_한_줄_안내와_함께_rule로_간다() {
        Resolution fromOption = resolver(env("OPENAI_API_KEY", "o-key")).resolve(Engine.AUTO, "claude");
        Resolution fromEnvironment = resolver(env("H2SPEC_LLM_PROVIDER", "gemini")).resolve(Engine.AUTO, null);

        assertEquals(new Resolution.UseRule("claude의 API 키(ANTHROPIC_API_KEY)가 없어 rule 엔진으로 변환합니다"), fromOption);
        assertEquals(new Resolution.UseRule("gemini의 API 키(GOOGLE_API_KEY 또는 GEMINI_API_KEY)가 없어 rule 엔진으로 변환합니다"),
                fromEnvironment);
    }

    @ParameterizedTest
    @EnumSource(Engine.class)
    void 모르는_회사_이름은_엔진과_상관없이_실패한다(Engine engine) {
        // 사용자가 LLM을 켜려고 적은 값이라 auto에서도 rule로 조용히 넘어가지 않는다
        ProviderResolver resolver = resolver(env("H2SPEC_LLM_PROVIDER", "chatgpt", "ANTHROPIC_API_KEY", "a-key"));

        assertEquals("알 수 없는 LLM 회사입니다: chatgpt (H2SPEC_LLM_PROVIDER). claude, openai, gemini 중 하나를 쓰세요",
                assertThrows(ProviderResolutionException.class, () -> resolver.resolve(engine, null)).getMessage());
        assertEquals("알 수 없는 LLM 회사입니다: gpt (--provider). claude, openai, gemini 중 하나를 쓰세요",
                assertThrows(ProviderResolutionException.class, () -> resolver.resolve(engine, " gpt ")).getMessage());
    }

    @Test
    void 회사_이름은_앞뒤_공백과_대소문자를_가리지_않는다() {
        Map<String, String> env = env("ANTHROPIC_API_KEY", "a-key", "OPENAI_API_KEY", "o-key");

        assertUses(CLAUDE, "a-key", resolver(env).resolve(Engine.LLM, " Claude "));
    }

    @Test
    void 빈_값은_설정하지_않은_것으로_보고_키의_앞뒤_공백은_뗀다() {
        Map<String, String> env = env("H2SPEC_LLM_PROVIDER", " ", "ANTHROPIC_API_KEY", "", "OPENAI_API_KEY", "  o-key\n",
                "GOOGLE_API_KEY", " \t");

        assertUses(OPENAI, "o-key", resolver(env).resolve(Engine.LLM, ""));
    }

    @Test
    void 기본_목록은_Claude만_고른다() {
        ProviderResolver resolver = new ProviderResolver(LlmProviders.ALL, env("ANTHROPIC_API_KEY", "a-key", "OPENAI_API_KEY", "o-key"));

        Resolution resolution = resolver.resolve(Engine.LLM, null);

        Resolution.UseLlm llm = assertInstanceOf(Resolution.UseLlm.class, resolution);
        assertEquals("claude", llm.provider().id());
        assertEquals("a-key", llm.apiKey());
    }

    @Test
    void 결과의_toString은_키를_가린다() {
        Resolution resolution = resolver(env("ANTHROPIC_API_KEY", "sk-ant-api03-SECRET")).resolve(Engine.LLM, null);

        assertEquals("UseLlm[provider=claude, apiKey=****]", resolution.toString());
        assertFalse(resolution.toString().contains("SECRET"));
    }

    @Test
    void 시스템_환경변수로_만든_resolver는_기본_목록을_쓴다() {
        // 실제 환경에 키가 있든 없든, 옵션으로 정한 회사가 기본 목록에 있어야 이름 오류가 나지 않는다
        Resolution resolution = ProviderResolver.fromSystemEnvironment().resolve(Engine.AUTO, "claude");

        assertTrue(resolution instanceof Resolution.UseLlm llm ? llm.provider().id().equals("claude")
                : resolution instanceof Resolution.UseRule, resolution::toString);
    }

    private static ProviderResolver resolver(Map<String, String> env) {
        return new ProviderResolver(PROVIDERS, env);
    }

    private static void assertUses(LlmProviderFactory provider, String apiKey, Resolution resolution) {
        assertEquals(new Resolution.UseLlm(provider, apiKey), resolution);
    }

    private static Map<String, String> env(String... namesAndValues) {
        Map<String, String> env = new HashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            env.put(namesAndValues[i], namesAndValues[i + 1]);
        }
        return env;
    }

    /** 아직 어댑터가 없는 회사를 흉내 낸다. 회사 결정은 클라이언트를 만들지 않는다 */
    private record FakeProvider(String id, List<String> apiKeyEnvironmentVariables) implements LlmProviderFactory {

        @Override
        public String defaultModel() {
            return id + "-model";
        }

        @Override
        public String defaultEffort() {
            return "medium";
        }

        @Override
        public LlmClient create(LlmSettings settings) {
            throw new UnsupportedOperationException();
        }
    }
}
