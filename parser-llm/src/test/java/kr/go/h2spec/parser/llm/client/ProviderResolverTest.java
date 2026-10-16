package kr.go.h2spec.parser.llm.client;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderResolverTest {

    @Test
    void 다른_구글_서비스와_같이_쓰는_GOOGLE_API_KEY는_회사를_고르는_데_세지_않고_Gemini를_고르면_키로_읽는다() {
        // Claude를 쓰던 사람의 셸에 다른 도구용 GOOGLE_API_KEY가 있어도 --provider 없이 그대로 Claude를 고른다
        ProviderResolver.Resolution.UseLlm claude = use(Map.of("ANTHROPIC_API_KEY", "sk-ant-x", "GOOGLE_API_KEY", "AIza-x"), null);
        assertEquals("claude", claude.provider().id());

        ProviderResolutionException both = assertThrows(ProviderResolutionException.class,
                () -> resolve(Map.of("ANTHROPIC_API_KEY", "sk-ant-x", "GEMINI_API_KEY", "AIza-g"), null));
        assertEquals("LLM 키가 여러 개입니다(claude, gemini). --provider 또는 H2SPEC_LLM_PROVIDER로 지정하세요", both.getMessage());

        ProviderResolver.Resolution.UseLlm google = use(Map.of("GOOGLE_API_KEY", "AIza-x"), "gemini");
        assertEquals("AIza-x", google.apiKey(), "Gemini를 직접 고르면 GOOGLE_API_KEY에서도 키를 읽는다");
        assertEquals("AIza-g", use(Map.of("GOOGLE_API_KEY", "AIza-x", "GEMINI_API_KEY", "AIza-g"), "gemini").apiKey(),
                "Gemini 전용 이름을 먼저 쓴다");
        assertEquals("openai", use(Map.of("OPENAI_API_KEY", "sk-proj-x"), null).provider().id());

        ProviderResolutionException none = assertThrows(ProviderResolutionException.class,
                () -> resolve(Map.of("GOOGLE_API_KEY", "AIza-x"), null));
        assertEquals("LLM API 키가 없습니다. 쓸 회사의 키를 환경변수(ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY)로 설정하세요. "
                + "GOOGLE_API_KEY는 다른 서비스와 같이 쓰는 이름이라 회사를 고르는 근거로 세지 않습니다. 이 키를 쓰려면 --provider gemini를 주세요",
                none.getMessage(), "GOOGLE_API_KEY만 있으면 왜 안 되는지와 어떻게 쓰는지 알려 준다");

        ProviderResolutionException empty = assertThrows(ProviderResolutionException.class, () -> resolve(Map.of(), null));
        assertEquals("LLM API 키가 없습니다. 쓸 회사의 키를 환경변수(ANTHROPIC_API_KEY, OPENAI_API_KEY, GEMINI_API_KEY)로 설정하세요",
                empty.getMessage());
    }

    private static ProviderResolver.Resolution resolve(Map<String, String> env, String provider) {
        return new ProviderResolver(LlmProviders.ALL, env).resolve(ProviderResolver.Engine.LLM, provider);
    }

    private static ProviderResolver.Resolution.UseLlm use(Map<String, String> env, String provider) {
        return (ProviderResolver.Resolution.UseLlm) resolve(env, provider);
    }
}
