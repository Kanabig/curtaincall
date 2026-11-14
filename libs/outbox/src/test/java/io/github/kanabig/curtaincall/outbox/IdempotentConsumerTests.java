package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** NFR-4 멱등 소비. */
class IdempotentConsumerTests extends OutboxIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    TestListeners.Listeners listeners;

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 부수 효과는 한 번이고 오프셋은 두 건 모두 커밋된다")
    void duplicateIsProcessedOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        long committedBefore =
                TestKafka.committedOffsets(bootstrapServers(), TestListeners.IDEM_GROUP, TestListeners.IDEM_TOPIC);
        int attemptsBefore = listeners.idemAttempts().get();

        kafkaTemplate
                .send(TestListeners.IDEM_TOPIC, "ord-1", envelope(eventId, "OrderCreated"))
                .get();
        kafkaTemplate
                .send(TestListeners.IDEM_TOPIC, "ord-1", envelope(eventId, "OrderCreated"))
                .get();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(listeners.idemAttempts().get() - attemptsBefore)
                        .isEqualTo(2));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(TestKafka.committedOffsets(
                                        bootstrapServers(), TestListeners.IDEM_GROUP, TestListeners.IDEM_TOPIC)
                                - committedBefore)
                        .isEqualTo(2));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_effect WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId))
                .isEqualTo(1);
        assertThat(meterRegistry.get("outbox.consumer.duplicates").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("처리가 계속 실패하면 백오프 재시도 3회 뒤 DLT로 가고 부수 효과와 처리 기록은 남지 않는다")
    void failureGoesToDltAfterRetries() throws Exception {
        String dlt = TestListeners.FAIL_TOPIC + ".DLT";
        double dltBefore = meterRegistry.get("outbox.consumer.dlt").counter().count();
        int attemptsBefore = listeners.failAttempts().get();
        String eventId = UUID.randomUUID().toString();

        kafkaTemplate
                .send(TestListeners.FAIL_TOPIC, "ord-1", envelope(eventId, "OrderCreated"))
                .get();

        List<ConsumerRecord<String, String>> dltRecords =
                TestKafka.readAll(bootstrapServers(), dlt, 1, Duration.ofSeconds(20));
        assertThat(dltRecords).extracting(ConsumerRecord::key).contains("ord-1");
        assertThat(listeners.failAttempts().get() - attemptsBefore).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_effect WHERE event_id = ?", Integer.class, eventId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId))
                .isZero();
        assertThat(meterRegistry.get("outbox.consumer.dlt").counter().count() - dltBefore)
                .isEqualTo(1.0);
        // DLT로 넘긴 레코드의 오프셋도 커밋돼야 재시작 뒤 다시 읽지 않는다
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(TestKafka.committedOffsets(
                                bootstrapServers(), TestListeners.FAIL_GROUP, TestListeners.FAIL_TOPIC))
                        .isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("봉투를 읽을 수 없으면 재시도 없이 바로 DLT로 간다")
    void unreadableGoesStraightToDlt() throws Exception {
        String dlt = TestListeners.BAD_TOPIC + ".DLT";
        int attemptsBefore = listeners.badAttempts().get();

        kafkaTemplate.send(TestListeners.BAD_TOPIC, "ord-bad", "이건 JSON이 아니다").get();

        List<ConsumerRecord<String, String>> dltRecords =
                TestKafka.readAll(bootstrapServers(), dlt, 1, Duration.ofSeconds(20));
        assertThat(dltRecords).extracting(ConsumerRecord::key).contains("ord-bad");
        assertThat(listeners.badAttempts().get() - attemptsBefore).isEqualTo(1);
    }

    @Test
    @DisplayName("소비 lag 지표가 Micrometer에 등록된다")
    void lagMetricIsRegistered() throws Exception {
        kafkaTemplate
                .send(
                        TestListeners.IDEM_TOPIC,
                        "ord-lag",
                        envelope(UUID.randomUUID().toString(), "OrderCreated"))
                .get();

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(meterRegistry.getMeters())
                        .anyMatch(meter -> meter.getId().getName().startsWith("kafka.consumer")
                                && meter.getId().getName().contains("lag")));
    }

    private static String envelope(String eventId, String type) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + type
                + "\",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\",\"aggregateId\":\"ord-1\",\"payload\":{}}";
    }
}
