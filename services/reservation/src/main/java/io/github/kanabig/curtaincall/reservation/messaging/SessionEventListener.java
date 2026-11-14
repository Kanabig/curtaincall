package io.github.kanabig.curtaincall.reservation.messaging;

import io.github.kanabig.curtaincall.outbox.config.OutboxAutoConfiguration;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.reservation.service.SeatInventoryService;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** [EVT-9, RSV-9] event.session 토픽의 회차 이벤트를 소비한다. 재고 생성은 SessionCreated, 시작 시각 갱신은 SessionUpdated도 처리한다. */
@Component
public class SessionEventListener {

    static final String SESSION_CREATED = "SessionCreated";
    static final String SESSION_UPDATED = "SessionUpdated";

    private final IdempotentConsumer consumer;
    private final SeatInventoryService inventoryService;

    public SessionEventListener(IdempotentConsumer consumer, SeatInventoryService inventoryService) {
        this.consumer = consumer;
        this.inventoryService = inventoryService;
    }

    @KafkaListener(
            topics = "${curtaincall.topics.session:event.session}",
            groupId = "reservation",
            containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
    void onSessionEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumer.consume(record, ack, this::handle);
    }

    private void handle(EventEnvelope envelope) {
        if (SESSION_CREATED.equals(envelope.eventType())) {
            inventoryService.requestInventory(sessionId(envelope), startAt(envelope));
        } else if (SESSION_UPDATED.equals(envelope.eventType())) {
            Instant startAt = startAt(envelope);
            if (startAt != null) {
                inventoryService.updateStartAt(sessionId(envelope), startAt);
            }
        }
    }

    private static Instant startAt(EventEnvelope envelope) {
        if (envelope.payload() == null || !envelope.payload().hasNonNull("startAt")) {
            return null;
        }
        return OffsetDateTime.parse(envelope.payload().get("startAt").asText()).toInstant();
    }

    private static long sessionId(EventEnvelope envelope) {
        if (envelope.payload() != null && envelope.payload().hasNonNull("sessionId")) {
            return envelope.payload().get("sessionId").asLong();
        }
        return Long.parseLong(envelope.aggregateId());
    }
}
