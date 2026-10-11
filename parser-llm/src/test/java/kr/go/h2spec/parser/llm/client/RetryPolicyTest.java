package kr.go.h2spec.parser.llm.client;

import kr.go.h2spec.parser.llm.client.LlmException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 재시도와 백오프(설계 4.4). 대기는 실제로 기다리지 않고 기록만 한다. */
class RetryPolicyTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void 다시_부를_수_있는_실패는_첫_대기부터_두_배씩_늘려_기다린다() throws Exception {
        String result = policy(3).call(failing(2, new LlmException(Kind.UNAVAILABLE, "과부하")));

        assertEquals("ok", result);
        assertEquals(3, calls.get());
        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4)), sleeps);
    }

    @Test
    void retry_after가_있으면_백오프_대신_그만큼_기다린다() throws Exception {
        LlmException limited = new LlmException(Kind.RATE_LIMITED, "한도", null, Duration.ofMillis(7500));

        policy(3).call(failing(1, limited));

        assertEquals(List.of(Duration.ofMillis(7500)), sleeps);
    }

    @Test
    void 대기는_상한에서_자른다() throws Exception {
        RetryPolicy policy = new RetryPolicy(5, Duration.ofSeconds(2), Duration.ofSeconds(5), sleeps::add);

        policy.call(failing(4, new LlmException(Kind.UNAVAILABLE, "과부하")));
        policy.call(failing(1, new LlmException(Kind.RATE_LIMITED, "한도", null, Duration.ofHours(1))));

        assertEquals(List.of(Duration.ofSeconds(2), Duration.ofSeconds(4), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5)), sleeps);
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"AUTH", "BAD_REQUEST"})
    void 키_오류와_잘못된_요청은_다시_부르지_않는다(Kind kind) {
        LlmException failure = new LlmException(kind, "실패");

        LlmException e = assertThrows(LlmException.class, () -> policy(3).call(failing(1, failure)));

        assertSame(failure, e);
        assertEquals(1, calls.get());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void 재시도를_다_쓰면_시도_횟수를_붙여_마지막_실패를_던진다() {
        LlmException failure = new LlmException(Kind.UNAVAILABLE, "과부하");

        LlmException e = assertThrows(LlmException.class, () -> policy(2).call(failing(10, failure)));

        assertEquals(Kind.UNAVAILABLE, e.kind());
        assertEquals("과부하 (3번 시도)", e.getMessage());
        assertSame(failure, e.getCause());
        assertEquals(3, calls.get());
    }

    @Test
    void 재시도_횟수가_0이면_한_번만_부르고_예외를_그대로_던진다() {
        LlmException failure = new LlmException(Kind.UNAVAILABLE, "과부하");

        assertSame(failure, assertThrows(LlmException.class, () -> policy(0).call(failing(1, failure))));
        assertEquals(1, calls.get());
    }

    @Test
    void 기다리다_중단되면_더_부르지_않고_중단_표시를_남긴다() {
        RetryPolicy policy = new RetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(1), duration -> {
            throw new InterruptedException();
        });
        LlmException failure = new LlmException(Kind.UNAVAILABLE, "과부하");

        try {
            LlmException e = assertThrows(LlmException.class, () -> policy.call(failing(1, failure)));

            assertSame(failure, e);
            assertEquals(1, calls.get());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            // 다음 테스트에 중단 표시가 넘어가지 않게 지운다
            Thread.interrupted();
        }
    }

    private RetryPolicy policy(int retryAttempts) {
        return new RetryPolicy(retryAttempts, RetryPolicy.DEFAULT_INITIAL_DELAY, RetryPolicy.DEFAULT_MAX_DELAY, sleeps::add);
    }

    /** 처음 {@code failures}번은 실패하고 그다음은 성공하는 호출 */
    private RetryPolicy.Attempt<String> failing(int failures, LlmException failure) {
        int start = calls.get();
        return () -> {
            if (calls.incrementAndGet() - start <= failures) {
                throw failure;
            }
            return "ok";
        };
    }
}
