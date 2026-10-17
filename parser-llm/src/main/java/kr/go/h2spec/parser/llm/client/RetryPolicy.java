package kr.go.h2spec.parser.llm.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * 다시 부르면 나을 수 있는 실패({@link LlmException.Kind#retryable()})를 지수 백오프로 다시 부른다.
 * <p>
 * 회사가 {@code retry-after}로 기다릴 시간을 알려 주면 그만큼 기다리고, 아니면 첫 대기부터 두 배씩 늘린다. 회사가 알려 준 시간이 상한보다
 * 길면 다시 부르지 않고 {@link LlmException.Kind#RETRY_LATER}로 바로 던진다. 회사는 그 시간 전에 다시 부르지 말라고 하므로, 상한만큼만
 * 기다려 부르면 남은 재시도만 써 버린다. 그 시간 전에는 다음 문서도 같은 이유로 거절되므로 부르는 쪽이 실행을 멈춘다.
 * 키·설정 오류, 잔액 문제, 잘못된 요청은 다시 불러도 결과가 같고, 중단된 응답(INTERRUPTED)은 다시 부르면 비용이 겹칠 수 있어 바로 던진다.
 * 대기에 흔들기(jitter)는 넣지 않는다. 한 프로세스가 같은 문서를 순서대로 부르므로 요청이 한꺼번에 몰리지 않고,
 * 대기 시간이 정해져 있어야 테스트가 실제로 기다리지 않고 확인할 수 있다.
 * <p>
 * 회사마다 다른 것은 실패를 {@link LlmException}으로 바꾸는 일뿐이라, 재시도는 어댑터 밖에 하나만 둔다.
 */
public final class RetryPolicy {

    /** 첫 재시도 전 기본 대기. 두 번째는 4초, 세 번째는 8초다 */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(2);
    /** 대기 상한. {@code retry-after}가 이보다 길면 기다리지 않고 실패로 알린다 */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(1);

    /** 기다린다. 대기 시간만 기록하는 것으로 바꾸면 실제로 기다리지 않고 시험할 수 있다 */
    @FunctionalInterface
    public interface Sleeper {

        /**
         * @param duration 기다릴 시간
         * @throws InterruptedException 기다리다 중단됐을 때
         */
        void sleep(Duration duration) throws InterruptedException;
    }

    /**
     * 한 번의 호출.
     *
     * @param <T> 호출 결과
     */
    @FunctionalInterface
    public interface Attempt<T> {

        /**
         * @return 호출 결과
         * @throws LlmException 호출이 실패했을 때
         */
        T call() throws LlmException;
    }

    private final int retryAttempts;
    private final Duration initialDelay;
    private final Duration maxDelay;
    private final Sleeper sleeper;

    /**
     * @param retryAttempts 첫 호출 뒤에 다시 부르는 최대 횟수
     * @param initialDelay 첫 재시도 전 대기. 재시도마다 두 배가 된다
     * @param maxDelay 대기 상한
     * @param sleeper 기다리는 방법
     */
    public RetryPolicy(int retryAttempts, Duration initialDelay, Duration maxDelay, Sleeper sleeper) {
        if (retryAttempts < 0) {
            throw new IllegalArgumentException("재시도 횟수는 0 이상이어야 합니다: " + retryAttempts);
        }
        if (initialDelay.isNegative() || maxDelay.isNegative()) {
            throw new IllegalArgumentException("대기 시간은 음수일 수 없습니다");
        }
        this.retryAttempts = retryAttempts;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * @param settings 재시도 횟수를 읽을 설정
     * @return 설정의 재시도 횟수와 기본 대기 시간으로 실제로 기다리는 정책
     */
    public static RetryPolicy of(LlmSettings settings) {
        return new RetryPolicy(settings.retryAttempts(), DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY,
                duration -> Thread.sleep(duration.toMillis()));
    }

    /**
     * 호출하고, 다시 부르면 나을 수 있는 실패면 기다렸다가 다시 부른다.
     *
     * @param attempt 한 번의 호출. 부를 때마다 같은 요청을 보내야 한다
     * @param <T> 호출 결과
     * @return 성공한 호출의 결과
     * @throws LlmException 다시 부를 수 없는 실패이거나, 재시도를 다 썼거나, 회사가 상한보다 긴 대기를 알렸을 때
     *                      ({@link LlmException.Kind#RETRY_LATER}). 두 번 이상 불렀으면 메시지에 시도 횟수를 붙인다
     */
    public <T> T call(Attempt<T> attempt) throws LlmException {
        for (int tries = 1; ; tries++) {
            try {
                return attempt.call();
            } catch (LlmException e) {
                // 재시도를 다 쓴 마지막 실패라도 긴 대기를 알렸으면 같게 알린다. 그 시간 전에는 다음 문서도 거절된다
                Optional<Duration> asked = e.kind().retryable()
                        ? e.retryAfter().filter(wait -> wait.compareTo(maxDelay) > 0)
                        : Optional.empty();
                if (asked.isPresent()) {
                    throw new LlmException(LlmException.Kind.RETRY_LATER, e.getMessage() + " (" + (tries == 1 ? "" : tries + "번 시도, ")
                            + "회사가 " + seconds(asked.get()) + "초 뒤에 다시 부르라고 해 기다리지 않았습니다)", e, asked.get());
                }
                // 중단된 스레드는 사용자가 멈춘 것이라 더 부르지 않는다
                if (!e.kind().retryable() || tries > retryAttempts || Thread.currentThread().isInterrupted()) {
                    throw tries == 1 ? e
                            : new LlmException(e.kind(), e.getMessage() + " (" + tries + "번 시도)", e, e.retryAfter().orElse(null));
                }
                try {
                    sleeper.sleep(delay(tries, e.retryAfter()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    e.addSuppressed(interrupted);
                    throw e;
                }
            }
        }
    }

    /** 올림한 초. 상한(1분)을 조금 넘은 60.4초가 "60초"로 보이지 않게 한다 */
    private static long seconds(Duration duration) {
        return (duration.toMillis() + 999) / 1000;
    }

    /** {@code tries}번째 호출이 실패한 뒤 기다릴 시간 */
    private Duration delay(int tries, Optional<Duration> retryAfter) {
        // 자리 이동은 넘치지 않게 막는다. 어차피 상한에서 잘린다
        Duration delay = retryAfter.orElseGet(() -> initialDelay.multipliedBy(1L << Math.min(tries - 1, 20)));
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }
}
