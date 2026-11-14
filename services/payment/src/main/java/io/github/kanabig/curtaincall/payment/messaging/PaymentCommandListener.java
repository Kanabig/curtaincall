package io.github.kanabig.curtaincall.payment.messaging;

import io.github.kanabig.curtaincall.outbox.config.OutboxAutoConfiguration;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import io.github.kanabig.curtaincall.payment.config.PaymentTopicsConfig;
import io.github.kanabig.curtaincall.payment.service.PaymentCommandService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/** [PAY-1, PAY-3, NFR-4] {@code payment.command} 소비. 명령 소비는 {@code processed_event}로 멱등하다. */
@Component
public class PaymentCommandListener {

    static final String GROUP_ID = "payment";
    public static final String LISTENER_ID = "payment-command-listener";

    private final IdempotentConsumer consumer;
    private final PaymentCommandService commandService;

    public PaymentCommandListener(IdempotentConsumer consumer, PaymentCommandService commandService) {
        this.consumer = consumer;
        this.commandService = commandService;
    }

    /** 컨슈머 3개는 명령 토픽의 파티션 수와 같다. 자동 시작은 {@code payment.command-listener.auto-start}로 끈다. */
    @KafkaListener(
            id = LISTENER_ID,
            topics = PaymentTopicsConfig.COMMAND_TOPIC,
            groupId = GROUP_ID,
            concurrency = "3",
            autoStartup = "${payment.command-listener.auto-start:true}",
            containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
    void onCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        consumer.consume(record, ack, commandService::handle);
    }
}
