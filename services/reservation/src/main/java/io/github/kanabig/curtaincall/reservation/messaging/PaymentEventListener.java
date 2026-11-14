package io.github.kanabig.curtaincall.reservation.messaging;

import io.github.kanabig.curtaincall.outbox.config.OutboxAutoConfiguration;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import io.github.kanabig.curtaincall.reservation.config.OrderTopicsConfig;
import io.github.kanabig.curtaincall.reservation.service.OrderSagaService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** [RSV-6, RSV-7] payment.payment 소비. 처리는 processed_event로 멱등하고 주문 상태 전이로 늦은 응답을 거른다. */
@Component
public class PaymentEventListener {

    public static final String LISTENER_ID = "payment-event-listener";

    private final IdempotentConsumer consumer;
    private final OrderSagaService sagaService;

    public PaymentEventListener(IdempotentConsumer consumer, OrderSagaService sagaService) {
        this.consumer = consumer;
        this.sagaService = sagaService;
    }

    @KafkaListener(
            id = LISTENER_ID,
            topics = OrderTopicsConfig.PAYMENT_TOPIC,
            groupId = "reservation",
            concurrency = "3",
            containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
    void onPaymentEvent(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumer.consume(record, ack, sagaService::handlePaymentEvent);
    }
}
