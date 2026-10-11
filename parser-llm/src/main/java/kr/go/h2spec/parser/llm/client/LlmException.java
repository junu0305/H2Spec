package kr.go.h2spec.parser.llm.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 회사 API 호출이 실패했다 (설계 6.1). CLI는 {@link #kind()}로 실패 처리(설계 7.3)를 가른다.
 * <p>
 * 메시지에는 API 키를 넣지 않는다(설계 10장). 회사가 돌려준 오류 글을 옮길 때도 어댑터가 키를 가린다.
 */
public class LlmException extends Exception {

    /** 실패 종류 */
    public enum Kind {
        /** 키가 틀렸거나 권한이 없다. 모든 문서가 같은 이유로 실패하므로 CLI는 전체를 멈춘다 */
        AUTH,
        /** 회사의 호출 한도에 걸렸다 */
        RATE_LIMITED,
        /** 회사 서버 오류·과부하, 네트워크 오류, 시간 초과 */
        UNAVAILABLE,
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
