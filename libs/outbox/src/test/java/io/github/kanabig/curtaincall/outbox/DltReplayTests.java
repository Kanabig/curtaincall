package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.outbox.consumer.DltEndpoint;
import io.github.kanabig.curtaincall.outbox.consumer.DltReplayer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

/** DLT 조회와 재투입(#73). */
class DltReplayTests extends OutboxIntegrationTest {

    private static final String DLT = TestListeners.REPLAY_TOPIC + ".DLT";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    TestListeners.Listeners listeners;

    @Autowired
    DltReplayer replayer;

    @Autowired
    DltEndpoint endpoint;

    @Test
    @DisplayName("DLT로 간 메시지를 원인 해소 뒤 재투입하면 처리되고, 다시 넣어도 한 번만 반영된다")
    void replayIsAppliedOnce() throws Exception {
        listeners.replayFailing().set(true);
        String eventId = UUID.randomUUID().toString();
        String key = "ord-" + eventId;
        ProducerRecord<String, String> record =
                new ProducerRecord<>(TestListeners.REPLAY_TOPIC, key, envelope(eventId));
        record.headers().add("X-Request-Id", "req-replay-1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();

        DltReplayer.Entry entry = await().atMost(Duration.ofSeconds(20)).until(() -> find(key), found -> found != null);
        assertThat(entry.originalTopic()).isEqualTo(TestListeners.REPLAY_TOPIC);
        assertThat(entry.originalOffset()).isNotNull();
        assertThat(entry.requestId()).isEqualTo("req-replay-1");
        assertThat(entry.exception()).contains("원인이 아직 풀리지 않았다");
        assertThat(count(eventId)).isZero();

        listeners.replayFailing().set(false);
        double replayedBefore = replayedCount();
        String id = entry.partition() + ":" + entry.offset();

        DltReplayer.Result first = endpoint.replay(DLT, id);
        assertThat(first.replayed()).isEqualTo(1);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(count(eventId)).isEqualTo(1));

        double duplicatesBefore =
                meterRegistry.get("outbox.consumer.duplicates").counter().count();
        endpoint.replay(DLT, id);
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(meterRegistry
                                        .get("outbox.consumer.duplicates")
                                        .counter()
                                        .count()
                                - duplicatesBefore)
                        .isEqualTo(1.0));
        assertThat(count(eventId)).isEqualTo(1);
        assertThat(replayedCount() - replayedBefore).isEqualTo(2.0);
    }

    @Test
    @DisplayName("재투입한 메시지는 키와 원래 헤더를 유지하고 DLT 기록용 헤더는 없다")
    void replayKeepsHeaders() throws Exception {
        listeners.replayFailing().set(true);
        String eventId = UUID.randomUUID().toString();
        String key = "ord-" + eventId;
        ProducerRecord<String, String> record =
                new ProducerRecord<>(TestListeners.REPLAY_TOPIC, key, envelope(eventId));
        record.headers().add("X-Request-Id", "req-replay-2".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).get();
        DltReplayer.Entry entry = await().atMost(Duration.ofSeconds(20)).until(() -> find(key), found -> found != null);
        int before = TestKafka.readAll(bootstrapServers(), TestListeners.REPLAY_TOPIC, 1000, Duration.ofSeconds(2))
                .size();

        replayer.replay(DLT, Set.of(entry.partition() + ":" + entry.offset()));

        List<ConsumerRecord<String, String>> all =
                TestKafka.readAll(bootstrapServers(), TestListeners.REPLAY_TOPIC, before + 1, Duration.ofSeconds(10));
        ConsumerRecord<String, String> replayed = all.get(all.size() - 1);
        assertThat(replayed.key()).isEqualTo(key);
        assertThat(replayed.value()).contains(eventId);
        assertThat(new String(replayed.headers().lastHeader("X-Request-Id").value(), StandardCharsets.UTF_8))
                .isEqualTo("req-replay-2");
        for (Header header : replayed.headers()) {
            assertThat(header.key()).doesNotStartWith("kafka_dlt-");
        }
    }

    @Test
    @DisplayName("없는 위치는 건너뛰고 사유를 돌려주며, 관리 엔드포인트는 읽는 토픽의 DLT 이름을 알려 준다")
    void unknownOffsetIsSkipped() {
        DltReplayer.Result result = replayer.replay(DLT, Set.of("0:999999"));

        assertThat(result.replayed()).isZero();
        assertThat(result.skipped())
                .singleElement()
                .asString()
                .contains("0:999999")
                .contains("DLT에 없음");
        assertThat(endpoint.topics()).contains(DLT, "test.fail.DLT", TestListeners.BAD_DLT);
    }

    private DltReplayer.Entry find(String key) {
        return replayer.list(DLT, 1000).stream()
                .filter(e -> key.equals(e.key()))
                .findFirst()
                .orElse(null);
    }

    private int count(String eventId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM test_effect WHERE event_id = ?", Integer.class, eventId);
    }

    private double replayedCount() {
        return meterRegistry.find("outbox.dlt.replayed").counters().stream()
                .mapToDouble(c -> c.count())
                .sum();
    }

    private static String envelope(String eventId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"OrderCreated\""
                + ",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\",\"aggregateId\":\"ord-1\",\"payload\":{}}";
    }
}
