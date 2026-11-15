package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisCircuitTests {

    private final RedisCircuit circuit = new RedisCircuit(3);

    @Test
    @DisplayName("[NFR-11] 연속 실패가 한도에 닿아야 열리고, 열리는 호출만 true다")
    void opensAfterConsecutiveFailures() {
        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.isOpen()).isFalse();

        assertThat(circuit.recordFailure()).isTrue();
        assertThat(circuit.isOpen()).isTrue();
        // 이미 열린 뒤 늦게 온 실패는 새로 여는 것이 아니다
        assertThat(circuit.recordFailure()).isFalse();
    }

    @Test
    @DisplayName("[NFR-11] 성공이 끼면 연속 실패 횟수가 0으로 돌아가 드문 실패로는 열리지 않는다")
    void successResetsFailureCount() {
        circuit.recordFailure();
        circuit.recordFailure();
        circuit.recordSuccess();

        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("[NFR-11] 닫는 호출만 true이고, 닫으면 실패 횟수도 0이라 다시 한도까지 실패해야 열린다")
    void closeResetsAndReopens() {
        assertThat(circuit.close()).isFalse();
        circuit.recordFailure();
        circuit.recordFailure();
        circuit.recordFailure();

        assertThat(circuit.close()).isTrue();
        assertThat(circuit.close()).isFalse();
        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.recordFailure()).isFalse();
        assertThat(circuit.recordFailure()).isTrue();
    }

    @Test
    @DisplayName("[NFR-11] 동시에 실패해도 여는 호출은 한 번이고, 동시에 닫아도 닫는 호출은 한 번이다")
    void concurrentOpenAndClose() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            AtomicInteger opened = new AtomicInteger();
            runConcurrently(pool, threads, () -> {
                if (circuit.recordFailure()) {
                    opened.incrementAndGet();
                }
            });
            assertThat(opened.get()).isEqualTo(1);
            assertThat(circuit.isOpen()).isTrue();

            AtomicInteger closed = new AtomicInteger();
            runConcurrently(pool, threads, () -> {
                if (circuit.close()) {
                    closed.incrementAndGet();
                }
            });
            assertThat(closed.get()).isEqualTo(1);
            assertThat(circuit.isOpen()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runConcurrently(ExecutorService pool, int threads, Runnable task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            done.add(pool.submit(() -> {
                start.await();
                task.run();
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : done) {
            f.get(10, TimeUnit.SECONDS);
        }
    }
}
