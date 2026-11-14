package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** RSV-6, RSV-7: Kafka를 거친 결제 응답 중복 소비와 명령 발행. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
@Import(PaymentEventListenerTests.Topics.class)
class PaymentEventListenerTests extends OrderIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Autowired
    OutboxRelay relay;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic paymentCommandTopic() {
            return TopicBuilder.name("payment.command")
                    .partitions(3)
                    .replicas(1)
                    .build();
        }

        @Bean
        NewTopic paymentEventTopic() {
            return TopicBuilder.name("payment.payment")
                    .partitions(3)
                    .replicas(1)
                    .build();
        }
    }

    @BeforeEach
    void startListener() {
        registry.getListenerContainer(PaymentEventListener.LISTENER_ID).start();
    }

    @AfterEach
    void stopListener() {
        registry.getListenerContainer(PaymentEventListener.LISTENER_ID).stop();
    }

    @Test
    @DisplayName("같은 승인 응답이 같은 이벤트 ID로 2번, 다른 ID로 3번 와도 확정은 한 번이다")
    void duplicatedApprovedConfirmsOnce() throws Exception {
        String orderId = orderInPayment(USER, 1L, 2L);
        String sameEvent = approvedEnvelope(UUID.randomUUID().toString(), orderId);

        send(orderId, sameEvent);
        send(orderId, sameEvent);
        for (int i = 0; i < 3; i++) {
            send(orderId, approvedEnvelope(UUID.randomUUID().toString(), orderId));
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(status(orderId)).isEqualTo("CONFIRMED"));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(processedEvents()).isEqualTo(4));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class))
                .isEqualTo(1);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed");
    }

    @Test
    @DisplayName("릴레이는 ApprovePayment를 orderId 키로 payment.command에 내보낸다")
    void relayPublishesCommand() throws Exception {
        String orderId = orderInPayment(USER, 1L);

        while (relay.pollOnce() > 0) {
            // 남은 행이 없을 때까지
        }

        List<ConsumerRecord<String, String>> sent = read("payment.command", orderId);
        assertThat(sent).hasSize(1);
        var envelope = objectMapper.readTree(sent.get(0).value());
        assertThat(envelope.get("eventType").asText()).isEqualTo("ApprovePayment");
        assertThat(envelope.get("payload").get("cardToken").asText()).isEqualTo("tok_approve");
    }

    private void send(String key, String value) throws Exception {
        kafkaTemplate.send("payment.payment", key, value).get();
    }

    private int processedEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class);
    }

    private static String approvedEnvelope(String eventId, String orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PaymentApproved\","
                + "\"occurredAt\":\"2026-11-14T12:00:00.000+09:00\",\"aggregateId\":\"" + orderId
                + "\",\"payload\":{\"paymentId\":\"pay_1\",\"orderId\":\"" + orderId
                + "\",\"amount\":220000,\"approvedAt\":\"2026-11-14T12:00:00Z\"}}";
    }

    private List<ConsumerRecord<String, String>> read(String topic, String key) {
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
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (found.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record);
                    }
                });
            }
        }
        return found;
    }
}
