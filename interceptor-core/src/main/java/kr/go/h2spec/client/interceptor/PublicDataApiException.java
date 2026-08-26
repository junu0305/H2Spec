package kr.go.h2spec.client.interceptor;

/**
 * HTTP Status는 200 OK이지만 응답 바디 상의 resultCode가 실패를 의미하거나
 * 알려진 에러 키워드가 포함된 경우 {@link PublicDataErrorInterceptor}가 던지는 예외.
 */
public class PublicDataApiException extends RuntimeException {

    private final String requestUri;
    private final int httpStatus;
    private final String resultCode;
    private final String resultMsg;
    private final String rawBodySnippet;

    public PublicDataApiException(String requestUri, int httpStatus, String resultCode,
                                   String resultMsg, String rawBodySnippet) {
        // 인증키가 로그로 유출되지 않도록 모든 문자열 필드를 마스킹한다
        // (에러 바디가 요청 URL을 에코하는 경우 resultMsg/바디 스니펫에도 키가 들어올 수 있음)
        super(String.format(
                "[H2Spec] 공공데이터 API 응답 오류 감지 (HTTP %d 이지만 실제로는 실패) - uri=%s, resultCode=%s, resultMsg=%s%s",
                httpStatus, ServiceKeyMasker.mask(requestUri), resultCode, ServiceKeyMasker.mask(resultMsg),
                guidance(resultCode, resultMsg)
        ));
        this.requestUri = ServiceKeyMasker.mask(requestUri);
        this.httpStatus = httpStatus;
        this.resultCode = resultCode;
        this.resultMsg = ServiceKeyMasker.mask(resultMsg);
        this.rawBodySnippet = ServiceKeyMasker.mask(rawBodySnippet);
    }

    /**
     * 사전에 있는 코드면 뜻과 조치를 덧붙인다. 없으면 메시지를 그대로 둔다.
     * 응답의 resultMsg가 이미 그 뜻이나 에러명을 담고 있으면 같은 말을 두 번 적지 않는다.
     */
    private static String guidance(String resultCode, String resultMsg) {
        return PublicDataErrorCatalog.lookup(resultCode, resultMsg)
                .map(entry -> meaningOf(entry, resultMsg) + String.format(" | 조치: %s", entry.action()))
                .orElse("");
    }

    private static String meaningOf(PublicDataErrorCatalog.ErrorCode entry, String resultMsg) {
        String message = resultMsg == null ? "" : resultMsg;
        if (message.contains(entry.meaning())) {
            return "";
        }
        return message.contains(entry.name())
                ? String.format(" | %s", entry.meaning())
                : String.format(" | %s: %s", entry.name(), entry.meaning());
    }

    public String getRequestUri() {
        return requestUri;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getResultCode() {
        return resultCode;
    }

    public String getResultMsg() {
        return resultMsg;
    }

    public String getRawBodySnippet() {
        return rawBodySnippet;
    }
}
