package kr.go.h2spec.parser.llm.client;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 어댑터를 만들 때 넘기는 설정 ({@link LlmProviderFactory#create}).
 * <p>
 * API 키는 환경변수로만 받는다. 이 record는 키를 담지만 {@link #toString()}은 키를 가린다.
 * 설정을 통째로 로그나 예외 메시지에 찍어도 키가 남지 않게 하기 위해서다.
 *
 * @param model 모델 ID. 회사별 기본값은 {@link LlmProviderFactory#defaultModel()}
 * @param effort 추론 깊이. 회사의 값을 그대로 쓴다(Claude는 {@code output_config.effort}).
 *               결과 캐시 키와 IR metadata에 남도록 기본값({@link LlmProviderFactory#defaultEffort()})도 적어서 보낸다
 * @param apiKey API 키
 * @param requestTimeout 응답 머리를 받을 때까지, 그리고 스트리밍 본문에서 다음 데이터가 올 때까지 기다리는 최대 시간.
 *                       응답 전체에 걸리는 시간은 제한하지 않는다. thinking이 길면 응답이 몇 분씩 걸리기 때문이다
 * @param retryAttempts 다시 부르면 나을 수 있는 실패({@link LlmException.Kind#retryable()}) 뒤에 다시 부르는 최대 횟수. 0이면 한 번만 부른다
 * @param baseUrl API 주소(스킴·호스트와 경로 앞부분). null이면 회사 기본 주소다. 프록시처럼 다른 주소를 거칠 때 쓴다
 * @param workspaceId 요청을 실행할 워크스페이스 ID. null이면 보내지 않는다. 한 워크스페이스에 묶이지 않은 키(여러 워크스페이스에서 쓰는
 *                    개인 키·서비스 계정 키)는 요청마다 워크스페이스를 밝혀야 한다(Claude는 {@code anthropic-workspace-id} 머리)
 */
public record LlmSettings(
        String model,
        String effort,
        String apiKey,
        Duration requestTimeout,
        int retryAttempts,
        URI baseUrl,
        String workspaceId) {

    /** 기본 대기 시간. 회사 SDK의 기본 타임아웃과 같은 10분이다. 정상 스트림은 ping 이벤트가 계속 와서 이만큼 멈추지 않는다 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofMinutes(10);
    /** 기본 재시도 횟수. 첫 호출을 더해 네 번까지 부른다 */
    public static final int DEFAULT_RETRY_ATTEMPTS = 3;
    /** {@link #toString()}에서 키 자리에 쓰는 글. 어댑터가 오류 글의 키를 가릴 때도 쓴다 */
    public static final String MASKED_API_KEY = "****";
    /** 워크스페이스 ID 꼴. 요청 머리에 실리므로 다른 글자는 받지 않는다 */
    private static final Pattern WORKSPACE_ID = Pattern.compile("wrkspc_[A-Za-z0-9_-]+");

    public LlmSettings {
        requireText(model, "모델 ID가 비었습니다");
        requireText(effort, "추론 깊이가 비었습니다");
        Objects.requireNonNull(apiKey, "apiKey");
        // 키는 HTTP 요청 머리에 실린다. JDK HttpClient는 머리 값에 쓸 수 없는 글자가 있으면 그 값을 메시지에 담아 예외를 던지므로
        // 여기서 키 값 없이 먼저 알린다. 복사하다 섞인 공백·줄바꿈이 흔한 원인이다
        if (apiKey.isEmpty() || !apiKey.chars().allMatch(c -> c > ' ' && c < 0x7f)) {
            throw new IllegalArgumentException("API 키가 비었거나 공백·제어 문자·ASCII 밖의 글자가 들어 있습니다");
        }
        Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("요청 대기 시간은 0보다 커야 합니다: " + requestTimeout);
        }
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("재시도 횟수는 0 이상이어야 합니다: " + retryAttempts);
        }
        if (baseUrl != null && !isHttpUrl(baseUrl)) {
            throw new IllegalArgumentException("API 주소는 질의 문자열 없는 http 또는 https 주소여야 합니다: " + baseUrl);
        }
        if (workspaceId != null && !WORKSPACE_ID.matcher(workspaceId).matches()) {
            throw new IllegalArgumentException("워크스페이스 ID는 wrkspc_로 시작해야 합니다: " + workspaceId);
        }
    }

    /**
     * 대기 시간·재시도 횟수는 기본값으로, 주소는 회사 기본 주소로 둔 설정을 만든다.
     *
     * @param model 모델 ID
     * @param effort 추론 깊이
     * @param apiKey API 키
     * @return 설정
     */
    public static LlmSettings of(String model, String effort, String apiKey) {
        return new LlmSettings(model, effort, apiKey, DEFAULT_REQUEST_TIMEOUT, DEFAULT_RETRY_ATTEMPTS, null, null);
    }

    /**
     * @param timeout 새 대기 시간
     * @return 대기 시간만 바꾼 설정
     */
    public LlmSettings withRequestTimeout(Duration timeout) {
        return new LlmSettings(model, effort, apiKey, timeout, retryAttempts, baseUrl, workspaceId);
    }

    /**
     * @param attempts 새 재시도 횟수
     * @return 재시도 횟수만 바꾼 설정
     */
    public LlmSettings withRetryAttempts(int attempts) {
        return new LlmSettings(model, effort, apiKey, requestTimeout, attempts, baseUrl, workspaceId);
    }

    /**
     * @param url 새 API 주소. null이면 회사 기본 주소
     * @return 주소만 바꾼 설정
     */
    public LlmSettings withBaseUrl(URI url) {
        return new LlmSettings(model, effort, apiKey, requestTimeout, retryAttempts, url, workspaceId);
    }

    /**
     * @param id 새 워크스페이스 ID. null이면 보내지 않는다
     * @return 워크스페이스 ID만 바꾼 설정
     */
    public LlmSettings withWorkspaceId(String id) {
        return new LlmSettings(model, effort, apiKey, requestTimeout, retryAttempts, baseUrl, id);
    }

    /** @return 키를 {@value #MASKED_API_KEY}로 가린 설정 */
    @Override
    public String toString() {
        return "LlmSettings[model=" + model + ", effort=" + effort + ", apiKey=" + MASKED_API_KEY
                + ", requestTimeout=" + requestTimeout + ", retryAttempts=" + retryAttempts + ", baseUrl=" + baseUrl
                + ", workspaceId=" + workspaceId + "]";
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isHttpUrl(URI url) {
        String scheme = url.getScheme() == null ? "" : url.getScheme().toLowerCase(Locale.ROOT);
        return (scheme.equals("http") || scheme.equals("https"))
                && url.getHost() != null && url.getRawQuery() == null && url.getRawFragment() == null;
    }
}
