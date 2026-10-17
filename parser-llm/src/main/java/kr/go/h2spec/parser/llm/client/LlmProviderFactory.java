package kr.go.h2spec.parser.llm.client;

import java.time.Duration;
import java.util.List;

/**
 * 회사별 어댑터 패키지가 구현하는 팩토리. ServiceLoader로 찾지 않고 {@link LlmProviders#ALL}에 적는다.
 */
public interface LlmProviderFactory {

    /** @return 회사 ID. {@code --provider}와 {@code H2SPEC_LLM_PROVIDER}에 쓰는 값이다 ({@code claude}, {@code openai}, {@code gemini}) */
    String id();

    /** @return 기본 모델 ID. 모델 이름은 코드 여러 곳에 쓰지 않고 여기 한 곳에 둔다. {@code --model}이 덮어쓴다 */
    String defaultModel();

    /**
     * @return 기본 추론 깊이. 회사의 값을 그대로 쓴다. 회사 API의 기본값과 같더라도 적어서 보내야
     *         결과 캐시 키와 IR metadata에 남고, 회사가 기본값을 바꿔도 결과가 조용히 달라지지 않는다
     */
    String defaultEffort();

    /**
     * @return 이 회사가 받는 추론 깊이 값(소문자). CLI가 부르기 전에 {@code --effort}를 확인한다.
     *         비어 있으면 확인하지 않고 회사에 그대로 보낸다
     */
    default List<String> effortLevels() {
        return List.of();
    }

    /**
     * @return API 키를 읽는 환경변수 이름. 둘 이상이면 앞의 이름이 우선한다.
     *         회사 결정은 이 이름들을 한 회사로 센다
     */
    List<String> apiKeyEnvironmentVariables();

    /**
     * @return 응답 머리와 응답 본문의 다음 조각을 기다리는 시간의 기본값({@link LlmSettings#requestTimeout()}). 추론하는 동안 아무것도
     *         보내지 않는 회사는 길게 잡는다. 이 시간 동안 멈추면 끊고 다시 부르지 않는다
     */
    default Duration defaultRequestTimeout() {
        return LlmSettings.DEFAULT_REQUEST_TIMEOUT;
    }

    /**
     * @return 이 회사 모델의 출력 토큰 한도. llm 엔진의 출력 상한과 한 번에 적을 오퍼레이션 수를 이 안에 맞춘다
     */
    default int maxOutputTokens() {
        return 128_000;
    }

    /**
     * @return 회사를 정하지 않았을 때 이 회사를 고를 근거로 세는 키 환경변수 이름. 기본은 {@link #apiKeyEnvironmentVariables()} 전부다.
     *         다른 서비스와 같이 쓰는 이름({@code GOOGLE_API_KEY})은 빼서, 그 키가 있다는 것만으로 이 회사를 고르거나 다른 회사와
     *         겹친다고 오류를 내지 않게 한다. 빼도 회사를 직접 고르면 그 이름에서 키를 읽는다
     */
    default List<String> detectionEnvironmentVariables() {
        return apiKeyEnvironmentVariables();
    }

    /**
     * @return 요청을 실행할 워크스페이스 ID를 읽는 환경변수 이름. 한 워크스페이스에 묶이지 않은 키는 요청마다 워크스페이스를 밝혀야 한다.
     *         그런 개념이 없는 회사는 빈 목록이다
     */
    default List<String> workspaceEnvironmentVariables() {
        return List.of();
    }

    /**
     * 모델 이름이 이 회사의 모델로 보이는지 본다. CLI가 다른 회사의 모델 이름으로 문서를 보내기 전에 멈추는 데 쓴다.
     * 모르는 이름(새 모델 계열, 미세 조정 모델)을 막지 않도록 확실한 앞부분만 본다.
     *
     * @param model 모델 ID
     * @return 이 회사의 모델 이름 꼴이면 true. 모르면 false
     */
    default boolean recognizesModel(String model) {
        return false;
    }

    /**
     * @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소
     * @return 이 회사의 클라이언트
     */
    LlmClient create(LlmSettings settings);
}
