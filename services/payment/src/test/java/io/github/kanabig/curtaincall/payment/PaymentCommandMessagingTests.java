package io.github.kanabig.curtaincall.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.github.kanabig.curtaincall.payment.messaging.PaymentCommandListener;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;

/** PAY-1, PAY-3, PAY-6, NFR-2: Kafka를 거친 명령 중복·재전송. */
class PaymentCommandMessagingTests extends PaymentIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    OutboxRelay relay;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @BeforeEach
    void startListener() {
        registry.getListenerContainer(PaymentCommandListener.LISTENER_ID).start();
    }

    @AfterEach
    void stopListener() {
        registry.getListenerContainer(PaymentCommandListener.LISTENER_ID).stop();
    }

    @Test
    @DisplayName("같은 ApprovePayment를 이벤트 ID를 바꿔 10번, 같은 이벤트 ID로 2번 보내도 PG 승인은 1건이고 응답은 payment.payment로 나간다")
    void duplicatedCommandsApproveOnce() throws Exception {
        String orderId = newOrderId();
        var sameEvent = approveCommand(orderId, "tok_approve");
        for (int i = 0; i < 10; i++) {
            send(orderId, objectMapper.writeValueAsString(approveEnvelope(approveCommand(orderId, "tok_approve"))));
        }
        send(orderId, objectMapper.writeValueAsString(approveEnvelope(sameEvent)));
        send(orderId, objectMapper.writeValueAsString(approveEnvelope(sameEvent)));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("APPROVED"));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(processedCommands()).isEqualTo(11));

        assertThat(PG.approvalRequestsFor(orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        // 릴레이가 응답을 payment.payment로 내보낸다. 재전송마다 현재 결과가 다시 나가므로 1건 이상이다
        while (relay.pollOnce() > 0) {
            // 남은 행이 없을 때까지
        }
        List<ConsumerRecord<String, String>> replies = readReplies(orderId);
        assertThat(replies).isNotEmpty();
        assertThat(replies).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(orderId);
            assertThat(record.value()).contains("\"PaymentApproved\"");
        });
    }

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send("payment.command", key, value).get();
    }

    private record Raw(String eventId, String eventType, String occurredAt, String aggregateId, Object payload) {}

    private Raw approveEnvelope(io.github.kanabig.curtaincall.outbox.message.EventEnvelope envelope) {
        return new Raw(
                envelope.eventId(),
                envelope.eventType(),
                envelope.occurredAt(),
                envelope.aggregateId(),
                envelope.payload());
    }

    private int processedCommands() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class);
        return count == null ? 0 : count;
    }

    private List<ConsumerRecord<String, String>> readReplies(String orderId) {
        Properties properties = new Properties();
        properties.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                String.join(",", kafkaConnectionDetails.getBootstrapServers()));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of("payment.payment"));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (orderId.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }
}
