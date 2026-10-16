package kr.go.h2spec.parser.llm.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * {@code --provider}·환경변수·키로 llm 엔진에 쓸 회사를 정한다.
 * <ol>
 *   <li>{@code --provider}</li>
 *   <li>{@code H2SPEC_LLM_PROVIDER}</li>
 *   <li>(llm만) 키 환경변수가 한 회사 것만 있으면 그 회사. 한 회사의 여러 키 이름은 한 회사로 센다. 다른 서비스와 같이 쓰는 이름
 *       ({@link LlmProviderFactory#detectionEnvironmentVariables()}에서 뺀 {@code GOOGLE_API_KEY})은 세지 않는다</li>
 *   <li>(llm만) 여러 회사 것이 있으면 오류</li>
 *   <li>정하지 못했거나 정한 회사의 키가 없으면 llm은 오류, auto는 한 줄 안내와 함께 rule로 진행</li>
 * </ol>
 * auto는 1·2번으로 회사가 정해질 때만 llm을 쓴다. {@code ANTHROPIC_API_KEY} 같은 범용 키는 다른 도구 때문에 이미 설정된 경우가 많아,
 * 키가 있다는 것만으로 문서를 외부로 보내고 과금하면 안 되기 때문이다. rule 엔진은 회사를 정하지 않고 키도 읽지 않으므로 이 클래스를 부르지 않는다.
 * <p>
 * 회사 목록과 환경변수를 생성자로 받는다. 테스트가 아직 없는 회사와 임의의 환경으로 모든 규칙을 확인할 수 있게 하기 위해서다.
 */
public final class ProviderResolver {

    /** h2spec 전용 회사 지정. 범용 키와 달리 사용자가 h2spec에 LLM을 켰다는 뜻이다 */
    public static final String PROVIDER_ENVIRONMENT_VARIABLE = "H2SPEC_LLM_PROVIDER";

    private static final String PROVIDER_OPTION = "--provider";

    /** 회사를 정하는 엔진. rule 엔진은 회사를 정하지 않으므로 없다 */
    public enum Engine {
        /** 사용자가 {@code --engine llm}을 골랐다. 정하지 못하면 오류다 */
        LLM,
        /** {@code --engine auto}(CLI는 아직 받지 않는다). h2spec 전용 설정이 있을 때만 llm을 쓰고 아니면 rule로 진행한다 */
        AUTO
    }

    /** 회사를 정한 결과 */
    public sealed interface Resolution {

        /**
         * llm 엔진으로 이 회사를 쓴다.
         *
         * @param provider 쓸 회사
         * @param apiKey 그 회사의 키 환경변수에서 읽은 키. 앞뒤 공백은 뗐다
         */
        record UseLlm(LlmProviderFactory provider, String apiKey) implements Resolution {

            public UseLlm {
                Objects.requireNonNull(provider, "provider");
                Objects.requireNonNull(apiKey, "apiKey");
            }

            /** @return 키를 가린 결과. 결과를 통째로 로그에 찍어도 키가 남지 않는다 */
            @Override
            public String toString() {
                return "UseLlm[provider=" + provider.id() + ", apiKey=" + LlmSettings.MASKED_API_KEY + "]";
            }
        }

        /**
         * auto에서 회사나 키가 없어 rule 엔진으로 진행한다.
         *
         * @param notice 사용자에게 보여 줄 한 줄 안내
         */
        record UseRule(String notice) implements Resolution {
        }
    }

    private final List<LlmProviderFactory> providers;
    private final Map<String, String> environment;

    /**
     * @param providers 고를 수 있는 회사. 이 순서로 안내 메시지에 적는다
     * @param environment 환경변수
     */
    public ProviderResolver(List<LlmProviderFactory> providers, Map<String, String> environment) {
        this.providers = List.copyOf(providers);
        this.environment = Map.copyOf(environment);
    }

    /**
     * @param engine llm 또는 auto
     * @param providerOption {@code --provider} 값. 주지 않았으면 null이나 빈 문자열
     * @return llm으로 쓸 회사와 키, 또는 (auto만) rule로 진행하라는 안내
     * @throws ProviderResolutionException llm에서 회사를 정하지 못했거나 정한 회사의 키가 없을 때, 그리고 엔진과 상관없이 회사 이름이 틀렸을 때
     */
    public Resolution resolve(Engine engine, String providerOption) {
        Objects.requireNonNull(engine, "engine");
        if (hasText(providerOption)) {
            return useNamed(engine, named(providerOption, PROVIDER_OPTION));
        }
        String fromEnvironment = environment.get(PROVIDER_ENVIRONMENT_VARIABLE);
        if (hasText(fromEnvironment)) {
            return useNamed(engine, named(fromEnvironment, PROVIDER_ENVIRONMENT_VARIABLE));
        }
        if (engine == Engine.AUTO) {
            return new Resolution.UseRule("LLM 회사가 지정되지 않아 rule 엔진으로 변환합니다. llm 엔진을 쓰려면 "
                    + PROVIDER_OPTION + " 또는 " + PROVIDER_ENVIRONMENT_VARIABLE + "로 회사를 지정하세요");
        }
        List<LlmProviderFactory> withKeys = providers.stream()
                .filter(provider -> value(provider.detectionEnvironmentVariables()).isPresent())
                .toList();
        if (withKeys.size() == 1) {
            LlmProviderFactory provider = withKeys.get(0);
            return new Resolution.UseLlm(provider, apiKey(provider).orElseThrow());
        }
        if (withKeys.size() > 1) {
            // 키가 PC마다 달라 같은 명령이 PC마다 다른 회사를 고르는 일을 막는다
            throw new ProviderResolutionException("LLM 키가 여러 개입니다(" + ids(withKeys) + "). "
                    + PROVIDER_OPTION + " 또는 " + PROVIDER_ENVIRONMENT_VARIABLE + "로 지정하세요");
        }
        throw new ProviderResolutionException("LLM API 키가 없습니다. 쓸 회사의 키를 환경변수("
                + providers.stream().flatMap(provider -> provider.detectionEnvironmentVariables().stream())
                        .collect(Collectors.joining(", "))
                + ")로 설정하세요" + uncountedKeyHint());
    }

    /**
     * 자동 선택에 세지 않는 키({@code GOOGLE_API_KEY})만 있으면, 키가 없다는 말만으로는 왜 안 되는지 알 수 없어 회사를 지정하라고 덧붙인다.
     */
    private String uncountedKeyHint() {
        List<LlmProviderFactory> uncounted = providers.stream()
                .filter(provider -> value(provider.apiKeyEnvironmentVariables()).isPresent())
                .toList();
        if (uncounted.isEmpty()) {
            return "";
        }
        String names = uncounted.stream()
                .flatMap(provider -> provider.apiKeyEnvironmentVariables().stream())
                .filter(name -> hasText(environment.get(name)))
                .collect(Collectors.joining(", "));
        return ". " + names + "는 다른 서비스와 같이 쓰는 이름이라 회사를 고르는 근거로 세지 않습니다. 이 키를 쓰려면 "
                + uncounted.stream().map(provider -> PROVIDER_OPTION + " " + provider.id()).collect(Collectors.joining(" 또는 "))
                + "를 주세요";
    }

    /** 1·2번으로 정한 회사. 키가 없으면 5번 규칙을 따른다 */
    private Resolution useNamed(Engine engine, LlmProviderFactory provider) {
        Optional<String> apiKey = apiKey(provider);
        if (apiKey.isPresent()) {
            return new Resolution.UseLlm(provider, apiKey.get());
        }
        String variables = String.join(" 또는 ", provider.apiKeyEnvironmentVariables());
        if (engine == Engine.LLM) {
            throw new ProviderResolutionException(provider.id() + "의 API 키가 없습니다. 환경변수 " + variables + "를 설정하세요");
        }
        return new Resolution.UseRule(provider.id() + "의 API 키(" + variables + ")가 없어 rule 엔진으로 변환합니다");
    }

    /**
     * 틀린 이름은 auto에서도 오류다. 사용자가 h2spec에 LLM을 켜려고 적은 값이라, rule로 조용히 넘어가면 오타를 알 길이 없다.
     */
    private LlmProviderFactory named(String id, String source) {
        return LlmProviders.find(providers, id).orElseThrow(() -> new ProviderResolutionException(
                "알 수 없는 LLM 회사입니다: " + id.strip() + " (" + source + "). " + ids(providers) + " 중 하나를 쓰세요"));
    }

    /** 회사의 키 환경변수 중 값이 있는 첫 이름의 값 */
    private Optional<String> apiKey(LlmProviderFactory provider) {
        return value(provider.apiKeyEnvironmentVariables());
    }

    /** 환경변수 중 값이 있는 첫 이름의 값. 빈 값과 공백뿐인 값은 설정하지 않은 것으로 본다 */
    private Optional<String> value(List<String> names) {
        return names.stream()
                .map(environment::get)
                .filter(ProviderResolver::hasText)
                .map(String::strip)
                .findFirst();
    }

    private static String ids(List<LlmProviderFactory> providers) {
        return providers.stream().map(LlmProviderFactory::id).collect(Collectors.joining(", "));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
