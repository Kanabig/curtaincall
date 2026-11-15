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

    private final RedisCircuit circuit = new RedisCircuit();

    @Test
    @DisplayName("[NFR-11] 처음 여는 호출만 true이고, 이미 열린 뒤 늦게 온 실패는 false")
    void opensOnce() {
        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.open()).isTrue();
        assertThat(circuit.open()).isFalse();
        assertThat(circuit.isOpen()).isTrue();
    }

    @Test
    @DisplayName("[NFR-11] 닫는 호출만 true이고, 닫힌 뒤 다시 실패하면 다시 열린다")
    void closeAndReopen() {
        assertThat(circuit.close()).isFalse();
        circuit.open();
        assertThat(circuit.close()).isTrue();
        assertThat(circuit.close()).isFalse();
        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.open()).isTrue();
    }

    @Test
    @DisplayName("[NFR-11] 동시에 열고 닫아도 열기 성공은 한 번이고 상태는 마지막 호출과 일치한다")
    void concurrentOpenAndClose() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger opened = new AtomicInteger();
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                done.add(pool.submit(() -> {
                    start.await();
                    if (circuit.open()) {
                        opened.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(opened.get()).isEqualTo(1);
            assertThat(circuit.isOpen()).isTrue();

            AtomicInteger closed = new AtomicInteger();
            CountDownLatch start2 = new CountDownLatch(1);
            done.clear();
            for (int i = 0; i < threads; i++) {
                done.add(pool.submit(() -> {
                    start2.await();
                    if (circuit.close()) {
                        closed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start2.countDown();
            for (Future<?> f : done) {
                f.get(10, TimeUnit.SECONDS);
            }
            assertThat(closed.get()).isEqualTo(1);
            assertThat(circuit.isOpen()).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
