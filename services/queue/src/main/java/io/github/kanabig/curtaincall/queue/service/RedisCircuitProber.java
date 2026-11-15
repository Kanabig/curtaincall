package io.github.kanabig.curtaincall.queue.service;

import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 서킷이 열려 있는 동안에만 주기적으로 PING을 보내 Redis가 돌아왔는지 본다. 성공하면 서킷을 닫는다. 요청 스레드가 아니라 스케줄러 스레드에서 돌아서, PING이 타임아웃까지
 * 걸려도 요청 지연에는 영향이 없다. 인스턴스마다 따로 PING한다. 주기는 {@code curtaincall.queue.redis-probe-interval}(기본 1s)이다.
 */
@Component
@ConditionalOnProperty(name = "curtaincall.queue.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class RedisCircuitProber {

    private static final Logger log = LoggerFactory.getLogger(RedisCircuitProber.class);

    private final RedisCircuit circuit;
    private final QueueRepository repository;
    private final Counter closed;

    RedisCircuitProber(RedisCircuit circuit, QueueRepository repository, MeterRegistry meters) {
        this.circuit = circuit;
        this.repository = repository;
        this.closed = Counter.builder("queue.redis.circuit.close")
                .description("PING에 성공해 서킷이 닫힌 횟수")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${curtaincall.queue.redis-probe-interval:1s}")
    void probe() {
        if (!circuit.isOpen()) {
            return;
        }
        try {
            repository.ping();
        } catch (DataAccessException e) {
            log.debug("Redis PING 실패, 서킷을 열어 둔다: {}", e.toString());
            return;
        }
        if (circuit.close()) {
            closed.increment();
            log.info("Redis가 응답해 서킷을 닫는다");
        }
    }
}
