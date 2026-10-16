package kr.go.h2spec.parser.llm.client;

import kr.go.h2spec.parser.llm.claude.ClaudeProviderFactory;
import kr.go.h2spec.parser.llm.gemini.GeminiProviderFactory;
import kr.go.h2spec.parser.llm.openai.OpenAiProviderFactory;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 쓸 수 있는 회사 목록.
 * <p>
 * ServiceLoader와 회사별 모듈을 두지 않고 목록을 코드로 적는다. 어댑터를 쓰는 곳이 cli 하나이고 cli가 모든 회사를 묶으므로
 * SPI로 얻는 것이 없다. 외부 어댑터 jar를 끼워야 하면 그때 SPI로 바꾼다.
 */
public final class LlmProviders {

    /** 회사를 더하면 여기에 한 줄을 더한다. 이 순서가 안내 메시지에 회사를 적는 순서다 */
    public static final List<LlmProviderFactory> ALL =
            List.of(new ClaudeProviderFactory(), new OpenAiProviderFactory(), new GeminiProviderFactory());

    private LlmProviders() {
    }

    /**
     * @param id 회사 ID. 앞뒤 공백과 대소문자는 가리지 않는다
     * @return 그 ID의 회사. 없으면 빈 값
     */
    public static Optional<LlmProviderFactory> find(String id) {
        return find(ALL, id);
    }

    /** @return 회사 ID 목록 ({@link #ALL} 순서) */
    public static List<String> ids() {
        return ALL.stream().map(LlmProviderFactory::id).toList();
    }

    /** {@link ProviderResolver}가 테스트용 목록에서도 같은 규칙으로 찾도록 목록을 받는다 */
    static Optional<LlmProviderFactory> find(List<LlmProviderFactory> providers, String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        return providers.stream().filter(provider -> provider.id().equals(normalized)).findFirst();
    }
}
