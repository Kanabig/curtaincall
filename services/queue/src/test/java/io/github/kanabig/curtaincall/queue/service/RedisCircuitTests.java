package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisCircuitTests {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RedisCircuit circuit = new RedisCircuit(Duration.ofSeconds(2), now::get);

    @Test
    @DisplayName("[NFR-11] 실패하면 열려 있는 동안 Redis를 부르지 않는다")
    void skipsWhileOpen() {
        assertThat(circuit.tryAcquire()).isTrue();
        assertThat(circuit.onFailure()).isTrue();
        // 열리기 전에 시작한 다른 호출의 실패는 새로 여는 것이 아니다
        assertThat(circuit.onFailure()).isFalse();

        assertThat(circuit.isOpen()).isTrue();
        assertThat(circuit.tryAcquire()).isFalse();
        now.addAndGet(Duration.ofMillis(1999).toNanos());
        assertThat(circuit.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("[NFR-11] 시간이 지나면 요청 하나만 시험 호출하고, 성공하면 닫는다")
    void halfOpenAllowsOneProbe() {
        circuit.onFailure();
        now.addAndGet(Duration.ofSeconds(2).toNanos());

        assertThat(circuit.tryAcquire()).isTrue();
        assertThat(circuit.tryAcquire()).isFalse();

        circuit.onSuccess();
        assertThat(circuit.isOpen()).isFalse();
        assertThat(circuit.tryAcquire()).isTrue();
        assertThat(circuit.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("[NFR-11] 시험 호출이 실패하면 다시 열린다")
    void probeFailureReopens() {
        circuit.onFailure();
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(circuit.tryAcquire()).isTrue();

        assertThat(circuit.onFailure()).isTrue();

        assertThat(circuit.tryAcquire()).isFalse();
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThat(circuit.tryAcquire()).isTrue();
    }
}
