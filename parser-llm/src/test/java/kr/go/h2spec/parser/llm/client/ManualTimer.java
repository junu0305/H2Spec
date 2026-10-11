package kr.go.h2spec.parser.llm.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 손으로 돌리는 시계. 본문 멈춤 감시({@link IdleTimeoutInputStream})를 실제로 기다리지 않고 시험한다.
 * 감시가 언제 깨는지를 테스트가 정하므로, 여러 빌드가 함께 돌아 기계가 바빠도 결과가 같다.
 */
public final class ManualTimer implements IdleTimeoutInputStream.Timer {

    private record Task(long dueNanos, Runnable task) {
    }

    private final List<Task> tasks = new ArrayList<>();
    private long nowNanos;

    @Override
    public synchronized long nanoTime() {
        return nowNanos;
    }

    @Override
    public synchronized void schedule(Runnable task, long delayNanos) {
        tasks.add(new Task(nowNanos + delayNanos, task));
        notifyAll();
    }

    /** 시계를 돌리고 때가 된 일을 돌린다. 돌다가 새로 정한 일도 때가 됐으면 돌린다 */
    public void advance(Duration duration) {
        synchronized (this) {
            nowNanos += duration.toNanos();
        }
        // 잠금 밖에서 돌린다. 감시가 스트림을 닫는 동안 읽는 스레드가 시계를 읽다가 막히지 않게 하기 위해서다
        for (List<Runnable> due = takeDue(); !due.isEmpty(); due = takeDue()) {
            due.forEach(Runnable::run);
        }
    }

    /** 깨울 일이 정해질 때까지 기다린다. 감시가 시작됐다는 뜻이다. 끝없이 기다리지 않게 테스트의 {@code @Timeout}이 막는다 */
    public synchronized void awaitScheduled() throws InterruptedException {
        while (tasks.isEmpty()) {
            wait();
        }
    }

    private synchronized List<Runnable> takeDue() {
        List<Runnable> due = new ArrayList<>();
        for (Iterator<Task> it = tasks.iterator(); it.hasNext(); ) {
            Task task = it.next();
            if (task.dueNanos() <= nowNanos) {
                due.add(task.task());
                it.remove();
            }
        }
        return due;
    }
}
