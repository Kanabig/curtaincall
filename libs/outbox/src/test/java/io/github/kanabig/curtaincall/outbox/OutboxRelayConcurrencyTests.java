package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.config.OutboxProperties;
import io.github.kanabig.curtaincall.outbox.relay.OutboxProducerSettings;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** NFR-4 릴레이 동시 실행. */
class OutboxRelayConcurrencyTests extends OutboxIntegrationTest {

    private static final int KEYS = 40;
    private static final int EVENTS_PER_KEY = 15;
    private static final int RELAYS = 3;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("릴레이 3개를 동시에 돌려도 중복 발행이 없고 키별 순서가 유지된다")
    void noDuplicatesAndOrderedPerKey() throws Exception {
        String topic = TestKafka.uniqueTopic("relay-concurrent");
        TestKafka.createTopic(bootstrapServers(), topic, 6);
        // 다음 이벤트가 앞 이벤트 뒤에 쌓이도록 키마다 순서대로 기록한다
        for (int seq = 0; seq < EVENTS_PER_KEY; seq++) {
            int current = seq;
            tx.executeWithoutResult(status -> {
                for (int key = 0; key < KEYS; key++) {
                    writer.append(topic, "key-" + key, "Step", Map.of("seq", current));
                }
            });
        }
        int total = KEYS * EVENTS_PER_KEY;

        ExecutorService executor = Executors.newFixedThreadPool(RELAYS);
        List<Future<Integer>> futures = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        for (int i = 0; i < RELAYS; i++) {
            OutboxRelay concurrentRelay = newRelay();
            futures.add(executor.submit(() -> {
                int published = 0;
                while (unpublished(topic) > 0 && System.nanoTime() < deadline) {
                    published += concurrentRelay.pollOnce();
                }
                return published;
            }));
        }
        int publishedSum = 0;
        for (Future<Integer> future : futures) {
            publishedSum += future.get();
        }
        executor.shutdown();

        assertThat(unpublished(topic)).isZero();
        assertThat(publishedSum).isEqualTo(total);
        List<ConsumerRecord<String, String>> records =
                TestKafka.readAll(bootstrapServers(), topic, total + 10, Duration.ofSeconds(20));
        Set<String> eventIds = new HashSet<>();
        Map<String, Integer> lastSeq = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(eventIds.add(envelope.get("eventId").asText()))
                    .as("중복 발행: %s", envelope.get("eventId"))
                    .isTrue();
            int seq = envelope.get("payload").get("seq").asInt();
            assertThat(seq).as("키 %s의 순서", record.key()).isEqualTo(lastSeq.getOrDefault(record.key(), -1) + 1);
            lastSeq.put(record.key(), seq);
        }
        assertThat(records).hasSize(total);
    }

    private OutboxRelay newRelay() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(
                OutboxProducerSettings.enforce(Map.of("bootstrap.servers", bootstrapServers()))));
        OutboxProperties.Relay properties = new OutboxProperties.Relay(
                false, Duration.ofMillis(200), 100, Duration.ofSeconds(5), Duration.ofDays(1), Duration.ofMinutes(1));
        return new OutboxRelay(repository, template, transactionManager, properties, new SimpleMeterRegistry());
    }
}
