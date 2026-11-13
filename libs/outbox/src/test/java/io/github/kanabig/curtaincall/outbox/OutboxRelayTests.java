package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.relay.OutboxMaintenance;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

/** NFR-4 릴레이 발행. */
class OutboxRelayTests extends OutboxIntegrationTest {

    @Autowired
    ObjectMapper objectMapper;

    @Test
    @DisplayName("릴레이가 outbox 행을 키와 추적 ID 헤더를 붙여 발행하고 발행 시각을 채운다")
    void publishesRow() throws Exception {
        String topic = TestKafka.uniqueTopic("relay-basic");
        TestKafka.createTopic(bootstrapServers(), topic, 3);
        MDC.put("requestId", "req-123");
        String eventId;
        try {
            eventId = tx.execute(status -> writer.append(topic, "ord-1", "OrderCreated", Map.of("orderId", "ord-1")));
        } finally {
            MDC.remove("requestId");
        }

        int published = relay.pollOnce();

        assertThat(published).isEqualTo(1);
        assertThat(unpublished(topic)).isZero();
        List<ConsumerRecord<String, String>> records =
                TestKafka.readAll(bootstrapServers(), topic, 1, Duration.ofSeconds(15));
        assertThat(records).hasSize(1);
        ConsumerRecord<String, String> record = records.get(0);
        assertThat(record.key()).isEqualTo("ord-1");
        assertThat(new String(record.headers().lastHeader("X-Request-Id").value(), StandardCharsets.UTF_8))
                .isEqualTo("req-123");
        assertThat(objectMapper.readTree(record.value()).get("eventId").asText())
                .isEqualTo(eventId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NOT NULL", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 키의 이벤트는 쓴 순서대로 발행된다")
    void keepsOrderPerKey() throws Exception {
        String topic = TestKafka.uniqueTopic("relay-order");
        TestKafka.createTopic(bootstrapServers(), topic, 3);
        for (int seq = 0; seq < 5; seq++) {
            int current = seq;
            tx.executeWithoutResult(status -> writer.append(topic, "ord-1", "OrderStep", Map.of("seq", current)));
        }

        int total = 0;
        int published;
        while ((published = relay.pollOnce()) > 0) {
            total += published;
        }

        assertThat(total).isEqualTo(5);
        List<ConsumerRecord<String, String>> records =
                TestKafka.readAll(bootstrapServers(), topic, 5, Duration.ofSeconds(15));
        List<Integer> sequence = records.stream()
                .map(r -> {
                    try {
                        return objectMapper
                                .readTree(r.value())
                                .get("payload")
                                .get("seq")
                                .asInt();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                })
                .toList();
        assertThat(sequence).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    @DisplayName("발행이 끝난 행은 보관 기간이 지나야 지워지고 미발행 행은 지우지 않는다")
    void deletesOnlyOldPublishedRows() {
        String topic = TestKafka.uniqueTopic("relay-retention");
        tx.executeWithoutResult(status -> {
            writer.append(topic, "a", "T", Map.of());
            writer.append(topic, "b", "T", Map.of());
            writer.append(topic, "c", "T", Map.of());
        });
        // a: 이틀 전에 발행, b: 방금 발행, c: 미발행
        jdbc.update("UPDATE outbox SET published_at = NOW(6) - INTERVAL 2 DAY WHERE message_key = 'a'");
        jdbc.update("UPDATE outbox SET published_at = NOW(6) WHERE message_key = 'b'");

        int deleted = new OutboxMaintenance(repository, Duration.ofDays(1)).deleteExpired();

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT message_key FROM outbox ORDER BY message_key", String.class))
                .containsExactly("b", "c");
    }

    @Test
    @DisplayName("미발행 건수 지표가 outbox 상태를 따라간다")
    void unpublishedGauge() {
        String topic = TestKafka.uniqueTopic("relay-gauge");
        tx.executeWithoutResult(status -> {
            writer.append(topic, "a", "T", Map.of());
            writer.append(topic, "b", "T", Map.of());
        });

        assertThat(meterRegistry.get("outbox.unpublished").gauge().value()).isEqualTo(2.0);
    }
}
