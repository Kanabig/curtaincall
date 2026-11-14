package io.github.kanabig.curtaincall.outbox.consumer;

import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [NFR-4] 멱등 소비. 비즈니스 처리와 {@code processed_event} 기록을 한 트랜잭션에 넣고, 커밋한 뒤에만 오프셋을 커밋한다.
 *
 * <p>같은 이벤트가 다시 오면 처리하지 않고 오프셋만 커밋한다. 처리 중 예외가 나면 트랜잭션이 롤백돼 기록도 사라지고, 오프셋은 커밋하지 않으므로 컨테이너의
 * 에러 핸들러가 재시도와 DLT를 맡는다. 리스너는 {@code containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY}를 써야 한다.
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final EventEnvelopes envelopes;
    private final ProcessedEventRepository processedEvents;
    private final TransactionTemplate transactionTemplate;
    private final Counter duplicates;

    public IdempotentConsumer(
            EventEnvelopes envelopes,
            ProcessedEventRepository processedEvents,
            TransactionTemplate transactionTemplate,
            MeterRegistry meterRegistry) {
        this.envelopes = envelopes;
        this.processedEvents = processedEvents;
        this.transactionTemplate = transactionTemplate;
        this.duplicates = Counter.builder("outbox.consumer.duplicates")
                .description("이미 처리한 이벤트라 건너뛴 횟수")
                .register(meterRegistry);
    }

    /**
     * @param handler 비즈니스 처리. 이 트랜잭션 안에서 실행되므로 도메인 변경과 새 outbox 기록이 함께 커밋된다
     */
    public void consume(ConsumerRecord<String, String> record, Acknowledgment ack, Consumer<EventEnvelope> handler) {
        EventEnvelope envelope = envelopes.read(record.value());
        Boolean processed = transactionTemplate.execute(status -> {
            if (!processedEvents.markProcessed(envelope.eventId())) {
                return false;
            }
            handler.accept(envelope);
            return true;
        });
        if (Boolean.FALSE.equals(processed)) {
            duplicates.increment();
            log.info("이미 처리한 이벤트라 건너뛴다: eventId={} type={}", envelope.eventId(), envelope.eventType());
        }
        ack.acknowledge();
    }
}
