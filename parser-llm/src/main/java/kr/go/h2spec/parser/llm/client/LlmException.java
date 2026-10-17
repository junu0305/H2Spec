package kr.go.h2spec.parser.llm.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 회사 API 호출이 실패했다. CLI는 {@link #kind()}로 실패 처리를 가른다.
 * <p>
 * 메시지에는 API 키를 넣지 않는다. 회사가 돌려준 오류 글을 옮길 때도 어댑터가 키를 가린다.
 */
public class LlmException extends Exception {

    /** 실패 종류 */
    public enum Kind {
        /** 키가 틀렸거나 권한이 없다(지원하지 않는 지역 포함). 모든 문서가 같은 이유로 실패하므로 CLI는 전체를 멈춘다 */
        AUTH,
        /**
         * 모델 이름, 그 모델이 받지 않는 추론 깊이, API 주소처럼 설정이 틀렸다. 모든 문서가 같은 이유로 실패하므로 CLI는 전체를 멈춘다
         */
        SETTINGS,
        /** 잔액이 없거나 사용 한도를 넘었다. 기다려도 풀리지 않고 모든 문서가 같은 이유로 실패하므로 CLI는 전체를 멈춘다 */
        QUOTA,
        /** 회사의 호출 한도에 걸렸다 */
        RATE_LIMITED,
        /**
         * 회사가 재시도 대기 상한({@link RetryPolicy})보다 긴 시간을 기다린 뒤 부르라고 했다({@code retry-after}). 그 시간 전에는 다른 문서도
         * 같은 이유로 거절되고, 회사는 그 전에 다시 부르지 말라고 하므로 CLI는 전체를 멈춘다
         */
        RETRY_LATER,
        /** 회사 서버 오류·과부하(스트림 중간에 알린 것 포함), 응답을 받기 전의 네트워크 오류 */
        UNAVAILABLE,
        /**
         * 회사가 요청을 받은 뒤 응답이 중간에 멈추거나 끊겼다. 응답 머리를 기다리다 대기 시간이 다 됐거나, 스트림이 대기 시간 동안 멈췄거나,
         * 스트림이 끝을 알리기 전에 끊겼거나 해석할 수 없게 망가진 경우다. 회사는 끊긴 뒤에도 끝까지 생성해 과금할 수 있어,
         * 다시 부르면 비용이 겹치므로 다시 부르지 않는다. 그 문서만 실패로 센다
         */
        INTERRUPTED,
        /** 요청이 잘못됐다. 같은 요청을 다시 보내도 결과가 같다 */
        BAD_REQUEST;

        /** @return 잠시 뒤 다시 부르면 나을 수 있는 실패인지. {@link #RATE_LIMITED}와 {@link #UNAVAILABLE}이다 */
        public boolean retryable() {
            return this == RATE_LIMITED || this == UNAVAILABLE;
        }
    }

    private final Kind kind;
    private final Duration retryAfter;

    /**
     * @param kind 실패 종류
     * @param message 사용자에게 보일 메시지. API 키를 담지 않는다
     */
    public LlmException(Kind kind, String message) {
        this(kind, message, null, null);
    }

    /**
     * @param kind 실패 종류
     * @param message 사용자에게 보일 메시지. API 키를 담지 않는다
     * @param cause 원인 예외. 없으면 null
     */
    public LlmException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    /**
     * @param kind 실패 종류
     * @param message 사용자에게 보일 메시지. API 키를 담지 않는다
     * @param cause 원인 예외. 없으면 null
     * @param retryAfter 회사가 알려 준, 다시 부르기 전에 기다릴 시간. 모르면 null
     */
    public LlmException(Kind kind, String message, Throwable cause, Duration retryAfter) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.retryAfter = retryAfter;
    }

    /** @return 실패 종류 */
    public Kind kind() {
        return kind;
    }

    /** @return 회사가 {@code retry-after}로 알려 준 대기 시간. 없으면 빈 값이고 {@link RetryPolicy}가 정한 백오프를 쓴다 */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
