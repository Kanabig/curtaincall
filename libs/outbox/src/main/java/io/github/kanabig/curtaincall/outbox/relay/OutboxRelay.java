package io.github.kanabig.curtaincall.outbox.relay;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import io.github.kanabig.curtaincall.outbox.config.OutboxProperties;
import io.github.kanabig.curtaincall.outbox.message.OutboxMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [NFR-4] 미발행 outbox 행을 Kafka로 보낸다. 인스턴스가 여러 개여도 {@code SKIP LOCKED}로 같은 행을 두 번 집지 않는다.
 *
 * <p>한 번의 폴링은 한 트랜잭션이다. 행을 잠그고 id 순서대로 하나씩 보내 브로커 응답(acks=all)을 받은 뒤 발행 시각을 채우고 커밋한다. 중간에 실패하면
 * 거기까지 보낸 행만 발행 처리하고 멈춘다. 커밋 직전에 죽으면 같은 메시지가 다시 나갈 수 있고(at-least-once), 소비 측이 걸러 낸다.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties.Relay properties;
    private final Counter published;
    private final Counter failed;

    public OutboxRelay(
            OutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager,
            OutboxProperties.Relay properties,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        // SKIP LOCKED 조회의 갭 락이 다른 릴레이의 발행 처리와 교착하지 않게 READ COMMITTED로 실행한다
        this.transactionTemplate = OutboxTransactions.readCommitted(transactionManager);
        this.properties = properties;
        this.published = Counter.builder("outbox.relay.published")
                .description("릴레이가 Kafka로 발행한 메시지 수")
                .register(meterRegistry);
        this.failed = Counter.builder("outbox.relay.failed")
                .description("발행에 실패해 다음 주기로 미룬 횟수")
                .register(meterRegistry);
    }

    /** 한 번 폴링한다. 발행한 행 수를 돌려준다. */
    public int pollOnce() {
        Integer count = transactionTemplate.execute(status -> publishBatch());
        return count == null ? 0 : count;
    }

    private int publishBatch() {
        List<OutboxMessage> batch = repository.lockUnpublished(properties.batchSize());
        List<Long> sent = new ArrayList<>();
        for (OutboxMessage message : batch) {
            try {
                send(message);
                sent.add(message.id());
            } catch (Exception e) {
                // 뒤의 행을 먼저 보내면 같은 키의 순서가 뒤집히므로 여기서 멈춘다
                failed.increment();
                log.warn("outbox 발행 실패, 다음 주기에 다시 시도한다: id={} topic={}", message.id(), message.topic(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                break;
            }
        }
        repository.markPublished(sent);
        published.increment(sent.size());
        return sent.size();
    }

    private void send(OutboxMessage message) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(message.topic(), message.key(), message.payload());
        if (message.requestId() != null) {
            record.headers().add(RequestIdContext.HEADER, message.requestId().getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
