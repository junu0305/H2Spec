package kr.go.h2spec.parser.llm.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 본문이 멈췄을 때 스스로 닫히는지 본다. 시간은 손으로 돌리는 시계로 정해 실제로 기다리지 않는다.
 * 실제 HTTP 응답 본문을 닫아 기다리던 읽기를 깨우는 것은 ClaudeClientTest가 가짜 서버로 본다.
 */
@Timeout(30)
class IdleTimeoutInputStreamTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ManualTimer timer = new ManualTimer();

    @Test
    void 대기_시간_동안_데이터가_오지_않으면_스스로_닫고_멈춤으로_표시한다() {
        StalledStream source = new StalledStream();
        IdleTimeoutInputStream in = IdleTimeoutInputStream.watch(source, TIMEOUT, timer);

        timer.advance(Duration.ofSeconds(9));
        assertFalse(in.timedOut());
        assertFalse(source.closed());

        timer.advance(Duration.ofSeconds(1));
        assertTrue(in.timedOut());
        assertTrue(source.closed());
        assertThrows(IOException.class, in::read);
    }

    @Test
    void 읽을_때마다_대기_시간을_새로_잰다() throws IOException {
        IdleTimeoutInputStream in = IdleTimeoutInputStream.watch(new ByteArrayInputStream(new byte[] {1, 2}), TIMEOUT, timer);

        timer.advance(Duration.ofSeconds(6));
        assertEquals(1, in.read());
        // 처음 정한 감시(10초)가 깨지만 마지막 읽기에서 6초뿐이라 닫지 않는다
        timer.advance(Duration.ofSeconds(6));
        assertEquals(2, in.read());
        timer.advance(Duration.ofSeconds(6));
        assertFalse(in.timedOut());

        // 마지막 읽기에서 10초가 지났다
        timer.advance(Duration.ofSeconds(4));
        assertTrue(in.timedOut());
    }

    @Test
    void 닫은_뒤에는_시간이_지나도_멈춤으로_보지_않는다() throws IOException {
        IdleTimeoutInputStream in = IdleTimeoutInputStream.watch(new ByteArrayInputStream(new byte[] {1}), TIMEOUT, timer);
        assertEquals(1, in.read());
        in.close();

        timer.advance(Duration.ofMinutes(1));

        assertFalse(in.timedOut());
    }

    @Test
    void 실제_시계로도_멈춘_스트림을_닫아_기다리던_읽기를_끝낸다() {
        // 운영의 지연 실행기 경로다. 멈춘 스트림은 언젠가 반드시 닫히므로 기계가 바빠 늦어져도 결과는 같다
        IdleTimeoutInputStream in = IdleTimeoutInputStream.watch(new StalledStream(), Duration.ofMillis(50));

        assertThrows(IOException.class, in::read);
        assertTrue(in.timedOut());
    }

    @Test
    void 대기_시간은_0보다_커야_한다() {
        assertThrows(IllegalArgumentException.class,
                () -> IdleTimeoutInputStream.watch(new ByteArrayInputStream(new byte[0]), Duration.ZERO, timer));
    }

    /** JDK 응답 본문처럼, 데이터가 오지 않으면 닫힐 때까지 기다리고 닫히면 읽기를 예외로 끝낸다 */
    private static final class StalledStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IOException("closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }

        boolean closed() {
            return closed.getCount() == 0;
        }
    }
}
