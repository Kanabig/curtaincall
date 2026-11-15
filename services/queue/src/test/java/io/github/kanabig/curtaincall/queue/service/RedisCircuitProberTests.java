package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.queue.config.QueueProperties;
import io.github.kanabig.curtaincall.queue.repository.EntryResult;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

/** [NFR-11] 서킷이 열린 동안 요청은 Redis를 부르지 않고, 복구 확인은 prober가 PING으로 한다. */
class RedisCircuitProberTests {

    private final QueueRepository repository = mock(QueueRepository.class);
    private final RedisCircuit circuit = new RedisCircuit(3);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final QueueService service = new QueueService(
            repository,
            new QueueProperties(
                    ZoneId.of("Asia/Seoul"),
                    Duration.ofMinutes(10),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(15),
                    1000,
                    Duration.ofSeconds(5),
                    3),
            mock(EntryTokenIssuer.class),
            meters,
            circuit);
    private final RedisCircuitProber prober = new RedisCircuitProber(circuit, repository, meters);

    private void openCircuit() {
        circuit.recordFailure();
        circuit.recordFailure();
        circuit.recordFailure();
    }

    @Test
    @DisplayName("열린 동안 요청은 Redis를 부르지 않고 바로 503이다")
    void openCircuitSkipsRedis() {
        openCircuit();

        assertThatThrownBy(() -> service.enter(1L, 1L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.status(1L, 1L)).isInstanceOf(BusinessException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Redis 호출이 연속으로 한도만큼 실패해야 서킷이 열리고, 열린 횟수는 한 번만 센다")
    void consecutiveFailuresOpenCircuitOnce() {
        doThrow(new RedisConnectionFailureException("down")).when(repository).enter(anyLong(), anyLong());

        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.enter(1L, 1L)).isInstanceOf(BusinessException.class);
        }
        assertThat(circuit.isOpen()).isFalse();
        assertThatThrownBy(() -> service.enter(1L, 2L)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.enter(1L, 3L)).isInstanceOf(BusinessException.class);

        assertThat(circuit.isOpen()).isTrue();
        assertThat(meters.get("queue.redis.circuit.open").counter().count()).isEqualTo(1.0);
        // 열린 뒤의 요청은 저장소를 부르지 않는다
        verify(repository, times(3)).enter(anyLong(), anyLong());
    }

    @Test
    @DisplayName("성공이 사이에 끼면 실패가 쌓이지 않아 서킷이 열리지 않는다")
    void successBetweenFailuresKeepsCircuitClosed() {
        doThrow(new RedisConnectionFailureException("slow")).when(repository).status(anyLong(), anyLong());
        doReturn(new EntryResult(EntryResult.Kind.NOT_IN_QUEUE, 0, 0, 0, false))
                .when(repository)
                .enter(anyLong(), anyLong());

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> service.status(1L, 1L)).isInstanceOf(BusinessException.class);
            service.enter(1L, 1L);
            if (i == 1) {
                assertThat(circuit.isOpen()).isFalse();
            }
        }

        assertThat(circuit.isOpen()).isFalse();
    }

    @Test
    @DisplayName("PING이 성공하면 닫히고, 다음 요청이 Redis를 부른다")
    void pingSuccessClosesCircuit() {
        openCircuit();

        prober.probe();

        assertThat(circuit.isOpen()).isFalse();
        assertThat(meters.get("queue.redis.circuit.close").counter().count()).isEqualTo(1.0);
        try {
            service.enter(1L, 1L);
        } catch (RuntimeException ignored) {
            // 목 저장소가 null을 돌려줘 변환 중 실패하는 것은 이 테스트의 관심사가 아니다
        }
        verify(repository).enter(1L, 1L);
    }

    @Test
    @DisplayName("PING이 실패하면 열린 채로 둔다")
    void pingFailureKeepsOpen() {
        openCircuit();
        doThrow(new RedisConnectionFailureException("down")).when(repository).ping();

        prober.probe();

        assertThat(circuit.isOpen()).isTrue();
        assertThat(meters.get("queue.redis.circuit.close").counter().count()).isZero();
    }

    @Test
    @DisplayName("닫혀 있으면 PING하지 않는다")
    void closedCircuitDoesNotPing() {
        prober.probe();

        verify(repository, never()).ping();
        verifyNoMoreInteractions(repository);
    }
}
