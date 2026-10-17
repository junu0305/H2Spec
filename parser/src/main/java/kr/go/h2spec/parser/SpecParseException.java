package kr.go.h2spec.parser;

import java.util.Objects;
import java.util.Optional;

/**
 * 엔진이 문서를 변환하지 못했다. 실패 종류로 CLI가 처리를 가를 수 있게 한다. 키·설정 오류, 잔액·사용 한도 문제, 긴 대기 요구는
 * 다른 문서도 같은 이유로 실패하므로 전체를 멈추고, 그 밖의 실패는 그 문서만 실패로 센다.
 * <p>
 * rule 엔진은 변환한 오퍼레이션이 없을 때 기존 파서와 같은 메시지로 {@link Kind#FAILED}만 쓴다.
 * 문서를 읽지 못한 것은 이 예외로 바꾸지 않고 {@link java.io.IOException} 그대로 던진다. CLI가 둘을 다른 문구로 알린다.
 * <p>
 * llm 엔진은 LLM이 답한 뒤에 실패하면(출력 잘림, 거절, JSON 파싱 실패, 변환한 오퍼레이션 없음) 엔진 정보를 함께 담는다.
 * 그 호출도 과금되므로 CLI가 실패한 문서의 토큰 사용량도 알리고 합계에 더한다.
 */
public class SpecParseException extends Exception {

    /** 실패 종류 */
    public enum Kind {
        /** API 키나 권한(지원하지 않는 지역 포함) 오류. 다른 문서도 같은 이유로 실패한다 */
        AUTH,
        /** LLM 설정(모델 이름, 추론 깊이, API 주소) 오류. 다른 문서도 같은 이유로 실패한다 */
        SETTINGS,
        /** LLM 계정의 잔액이나 사용 한도 문제. 다른 문서도 같은 이유로 실패한다 */
        QUOTA,
        /**
         * LLM 회사가 재시도 대기 상한보다 긴 시간({@code retry-after}) 뒤에 다시 부르라고 했다(호출 한도, 점검 중인 서버 등). 그 시간 전에는
         * 다른 문서도 같은 이유로 거절된다
         */
        RETRY_LATER,
        /** 429·5xx·네트워크 오류가 재시도한 뒤에도 이어졌거나, 응답이 중간에 멈추거나 끊겼다. 나중에 다시 하면 될 수 있다 */
        RETRYABLE,
        /** 그 밖의 실패. 변환한 오퍼레이션이 없거나 LLM 응답을 결과로 쓸 수 없는 경우(출력 잘림, 거절, JSON 파싱 실패) */
        FAILED
    }

    /** 실패 종류 */
    private final Kind kind;
    /** 실패하기 전에 엔진이 쓴 회사·모델·토큰. 엔진이 일을 하지 않았으면(LLM을 부르기 전 실패, rule 엔진) null */
    private final SpecParseResult.Engine engine;

    /**
     * 원인 예외 없이 만든다.
     *
     * @param kind    실패 종류
     * @param message 사용자에게 그대로 보여 줄 메시지
     */
    public SpecParseException(Kind kind, String message) {
        this(kind, message, null);
    }

    /**
     * 원인 예외와 함께 만든다.
     *
     * @param kind    실패 종류
     * @param message 사용자에게 그대로 보여 줄 메시지
     * @param cause   원인 예외. 없으면 null
     */
    public SpecParseException(Kind kind, String message, Throwable cause) {
        this(kind, message, cause, null);
    }

    /**
     * 실패하기 전에 엔진이 쓴 자원과 함께 만든다.
     *
     * @param kind    실패 종류
     * @param message 사용자에게 그대로 보여 줄 메시지
     * @param cause   원인 예외. 없으면 null
     * @param engine  실패하기 전에 엔진이 쓴 회사·모델·토큰. 없으면 null
     */
    public SpecParseException(Kind kind, String message, Throwable cause, SpecParseResult.Engine engine) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.engine = engine;
    }

    /**
     * 실패 종류를 돌려준다.
     *
     * @return 실패 종류
     */
    public Kind kind() {
        return kind;
    }

    /**
     * 실패하기 전에 엔진이 쓴 회사·모델·토큰을 돌려준다.
     *
     * @return 엔진 정보. LLM을 부르기 전에 실패했거나 rule 엔진이면 비어 있다
     */
    public Optional<SpecParseResult.Engine> engine() {
        return Optional.ofNullable(engine);
    }
}
