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

/**
 * [EVT-9, RSV-9] event.session 토픽의 회차 이벤트를 소비한다. event.session은 compaction 토픽이라 SessionCreated가 이후 SessionUpdated로 대체될 수 있다.
 * 두 이벤트 모두 전체 값을 싣고 재고 등록(insertPending)은 멱등이므로, SessionUpdated도 재고가 없으면 같은 경로로 등록한 뒤 시작 시각과 가격을 맞춘다.
 * SessionDeleted는 아직 재고가 없는 회차의 재고 생성을 막는다.
 */
@Component
public class SessionEventListener {

    static final String SESSION_CREATED = "SessionCreated";
    static final String SESSION_UPDATED = "SessionUpdated";
    static final String SESSION_DELETED = "SessionDeleted";
    static final String SESSION_SUSPENDED = "SessionSuspended";

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
        if (SESSION_CREATED.equals(envelope.eventType()) || SESSION_SUSPENDED.equals(envelope.eventType())) {
            // 중지된 회차라도 재고는 있어도 된다(신규 점유는 판매 상태로 거절한다). compaction으로 중지 이벤트만 남은 회차도 재고를 만든다
            inventoryService.requestInventory(sessionId(envelope), startAt(envelope));
        } else if (SESSION_DELETED.equals(envelope.eventType())) {
            inventoryService.markDeleted(sessionId(envelope));
        } else if (SESSION_UPDATED.equals(envelope.eventType())) {
            long sessionId = sessionId(envelope);
            Instant startAt = startAt(envelope);
            inventoryService.requestInventory(sessionId, startAt);
            if (startAt != null) {
                inventoryService.updateStartAt(sessionId, startAt);
            }
            if (envelope.payload() != null && envelope.payload().hasNonNull("prices")) {
                inventoryService.requestPriceSync(
                        sessionId, OffsetDateTime.parse(envelope.occurredAt()).toInstant());
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
