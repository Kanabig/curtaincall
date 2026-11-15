package io.github.kanabig.curtaincall.notification.messaging;

import io.github.kanabig.curtaincall.notification.service.NotificationService;
import io.github.kanabig.curtaincall.outbox.config.OutboxAutoConfiguration;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * [NTF-1~3] reservation.order 토픽을 소비한다.
 *
 * <p>멱등 기록과 메일 발송을 한 트랜잭션에 둔다: 기록을 먼저 넣고 메일을 보낸 뒤 커밋한다. 발송이 실패하면 기록이 롤백되어 재시도가 다시 보내고, 발송 직후 커밋 전에 프로세스가 죽는
 * 경우에만 같은 메일이 한 번 더 나갈 수 있다(유실보다 중복을 택했다).
 */
@Component
public class OrderEventListener {

    private final IdempotentConsumer consumer;
    private final NotificationService service;

    public OrderEventListener(IdempotentConsumer consumer, NotificationService service) {
        this.consumer = consumer;
        this.service = service;
    }

    @KafkaListener(
            topics = "${curtaincall.topics.order:reservation.order}",
            groupId = "notification",
            containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
    void onOrderEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumer.consume(record, ack, service::handle);
    }
}
