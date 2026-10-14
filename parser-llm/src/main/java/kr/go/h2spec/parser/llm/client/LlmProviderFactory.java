package kr.go.h2spec.parser.llm.client;

import java.util.List;

/**
 * 회사별 어댑터 패키지가 구현하는 팩토리. ServiceLoader로 찾지 않고 {@link LlmProviders#ALL}에 적는다.
 */
public interface LlmProviderFactory {

    /** @return 회사 ID. {@code --provider}와 {@code H2SPEC_LLM_PROVIDER}에 쓰는 값이다 (지금은 {@code claude}만 있다) */
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
     * @return 요청을 실행할 워크스페이스 ID를 읽는 환경변수 이름. 한 워크스페이스에 묶이지 않은 키는 요청마다 워크스페이스를 밝혀야 한다.
     *         그런 개념이 없는 회사는 빈 목록이다
     */
    default List<String> workspaceEnvironmentVariables() {
        return List.of();
    }

    /**
     * @param settings 모델, 추론 깊이, 키, 대기 시간, 재시도 횟수, 주소
     * @return 이 회사의 클라이언트
     */
    LlmClient create(LlmSettings settings);
}
