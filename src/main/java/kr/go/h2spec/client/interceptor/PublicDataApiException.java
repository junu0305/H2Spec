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
        super(String.format(
                "[H2Spec] 공공데이터 API 응답 오류 감지 (HTTP %d 이지만 실제로는 실패) - uri=%s, resultCode=%s, resultMsg=%s",
                httpStatus, requestUri, resultCode, resultMsg
        ));
        this.requestUri = requestUri;
        this.httpStatus = httpStatus;
        this.resultCode = resultCode;
        this.resultMsg = resultMsg;
        this.rawBodySnippet = rawBodySnippet;
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
