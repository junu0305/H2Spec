package kr.go.h2spec.parser.llm.client;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 다음 데이터가 정한 시간 안에 오지 않으면 스스로 닫히는 입력 스트림. 스트리밍 응답 본문을 감싸 쓴다.
 * <p>
 * JDK {@code HttpClient}의 요청 타임아웃은 응답 머리를 받을 때까지만 재고 본문에는 걸리지 않는다. 반쯤 끊긴 연결에서 본문이 멈추면
 * 읽기가 끝없이 기다리므로 읽기 사이의 빈 시간을 따로 잰다. 응답 전체 시간은 재지 않는다. thinking이 길면 응답이 몇 분씩 걸린다.
 * Claude는 그동안에도 ping 이벤트를 보내 데이터가 계속 오지만, 추론하는 동안 아무것도 보내지 않는 회사도 있어 대기 시간은 회사마다
 * 정한다({@link LlmProviderFactory#defaultRequestTimeout()}).
 * <p>
 * 시간이 지나면 다른 스레드에서 {@link #close()}를 부르고 {@link #timedOut()}을 true로 둔다. JDK의 응답 본문 스트림은 닫힐 때
 * 기다리던 읽기를 깨워 {@link IOException}으로 끝낸다. 읽는 쪽은 그 예외가 멈춤 때문인지를 {@link #timedOut()}으로 가린다.
 */
public final class IdleTimeoutInputStream extends FilterInputStream {

    /** 시간을 재고 감시를 깨우는 방법. 손으로 돌리는 시계로 바꾸면 실제로 기다리지 않고 시험할 수 있다 */
    public interface Timer {

        /** 실제 시계와 JDK의 지연 실행기. 감시용 스레드를 따로 두지 않는다. 감시는 대기 시간마다 한 번 깨는 정도라 가볍다 */
        Timer SYSTEM = new Timer() {
            @Override
            public long nanoTime() {
                return System.nanoTime();
            }

            @Override
            public void schedule(Runnable task, long delayNanos) {
                CompletableFuture.delayedExecutor(delayNanos, TimeUnit.NANOSECONDS).execute(task);
            }
        };

        /** @return 지금 시각(나노초). {@link System#nanoTime()}처럼 두 값의 차이만 뜻이 있다 */
        long nanoTime();

        /**
         * @param task 깨울 일. 다른 스레드에서 돌 수 있다
         * @param delayNanos 기다릴 시간(나노초)
         */
        void schedule(Runnable task, long delayNanos);
    }

    private final long timeoutNanos;
    private final Timer timer;
    private volatile long lastReadNanos;
    private volatile boolean closed;
    private volatile boolean timedOut;

    private IdleTimeoutInputStream(InputStream in, Duration timeout, Timer timer) {
        super(Objects.requireNonNull(in, "in"));
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("대기 시간은 0보다 커야 합니다: " + timeout);
        }
        this.timeoutNanos = timeout.toNanos();
        this.timer = Objects.requireNonNull(timer, "timer");
        this.lastReadNanos = timer.nanoTime();
    }

    /**
     * 실제 시계로 감시한다.
     *
     * @param in 감쌀 스트림
     * @param timeout 읽기 사이에 기다리는 최대 시간
     * @return 감시를 시작한 스트림
     */
    public static IdleTimeoutInputStream watch(InputStream in, Duration timeout) {
        return watch(in, timeout, Timer.SYSTEM);
    }

    /**
     * @param in 감쌀 스트림
     * @param timeout 읽기 사이에 기다리는 최대 시간
     * @param timer 시간을 재고 감시를 깨우는 방법
     * @return 감시를 시작한 스트림
     */
    public static IdleTimeoutInputStream watch(InputStream in, Duration timeout, Timer timer) {
        IdleTimeoutInputStream stream = new IdleTimeoutInputStream(in, timeout, timer);
        // 생성자 안에서 감시를 걸면 다 만들어지기 전의 객체가 감시 스레드로 넘어가므로 만든 뒤에 건다
        stream.watchAfter(stream.timeoutNanos);
        return stream;
    }

    @Override
    public int read() throws IOException {
        try {
            return super.read();
        } finally {
            lastReadNanos = timer.nanoTime();
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        try {
            return super.read(buffer, offset, length);
        } finally {
            lastReadNanos = timer.nanoTime();
        }
    }

    /** @return 데이터가 멈춰 스스로 닫혔는지 */
    public boolean timedOut() {
        return timedOut;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        super.close();
    }

    private void watchAfter(long delayNanos) {
        timer.schedule(this::check, delayNanos);
    }

    private void check() {
        if (closed) {
            return;
        }
        long idleNanos = timer.nanoTime() - lastReadNanos;
        if (idleNanos < timeoutNanos) {
            watchAfter(timeoutNanos - idleNanos);
            return;
        }
        timedOut = true;
        try {
            close();
        } catch (IOException e) {
            // 닫다가 난 오류는 읽는 쪽이 다음 읽기에서 겪는다
        }
    }
}
