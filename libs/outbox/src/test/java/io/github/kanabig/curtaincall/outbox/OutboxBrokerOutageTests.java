package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import io.github.kanabig.curtaincall.outbox.config.OutboxProperties;
import io.github.kanabig.curtaincall.outbox.relay.OutboxProducerSettings;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.kafka.KafkaContainer;

/**
 * [NFR-4] 브로커를 멈췄다 다시 띄우는 시나리오라 공유 브로커 대신 전용 컨테이너를 쓴다. 재시작 뒤에도 같은 주소로 접속하도록 호스트 포트를 고정한다.
 */
class OutboxBrokerOutageTests extends OutboxIntegrationTest {

    private static int port;
    private static KafkaContainer broker;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PlatformTransactionManager transactionManager;

    @BeforeAll
    static void startBroker() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        broker = new KafkaContainer("apache/kafka:4.3.2")
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig()
                        .withPortBindings(new PortBinding(Ports.Binding.bindPort(port), new ExposedPort(9092))));
        broker.start();
    }

    @AfterAll
    static void stopBroker() {
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("브로커가 멈춰 있는 동안 쓰기는 유실되지 않고, 재시작하면 남은 행이 키별 순서대로 발행된다")
    void convergesAfterBrokerRestart() throws Exception {
        String bootstrap = broker.getBootstrapServers();
        String topic = TestKafka.uniqueTopic("relay-outage");
        TestKafka.createTopic(bootstrap, topic, 3);
        OutboxRelay outageRelay = relayTo(bootstrap);

        // 브로커가 살아 있을 때: seq 0..2
        appendRound(topic, 0, 3);
        drain(outageRelay, topic, Duration.ofSeconds(30));
        assertThat(unpublished(topic)).isZero();

        // 브로커 중단: 쓰기는 DB에만 쌓이고 발행은 실패하되 예외로 번지지 않는다
        broker.getDockerClient().stopContainerCmd(broker.getContainerId()).exec();
        appendRound(topic, 3, 7);
        assertThat(outageRelay.pollOnce()).isZero();
        assertThat(unpublished(topic)).isEqualTo(4 * KEYS);

        // 재시작: 남은 행이 모두 나간다
        broker.getDockerClient().startContainerCmd(broker.getContainerId()).exec();
        waitUntilReachable(bootstrap);
        drain(outageRelay, topic, Duration.ofSeconds(90));

        assertThat(unpublished(topic)).isZero();
        List<ConsumerRecord<String, String>> records =
                TestKafka.readAll(bootstrap, topic, 7 * KEYS + 10, Duration.ofSeconds(30));
        Set<String> eventIds = new HashSet<>();
        Map<String, Integer> lastSeq = new HashMap<>();
        for (ConsumerRecord<String, String> record : records) {
            JsonNode envelope = objectMapper.readTree(record.value());
            assertThat(eventIds.add(envelope.get("eventId").asText()))
                    .as("중복 발행")
                    .isTrue();
            int seq = envelope.get("payload").get("seq").asInt();
            assertThat(seq).as("키 %s의 순서", record.key()).isEqualTo(lastSeq.getOrDefault(record.key(), -1) + 1);
            lastSeq.put(record.key(), seq);
        }
        assertThat(records).hasSize(7 * KEYS);
    }

    private static final int KEYS = 5;

    private void appendRound(String topic, int fromSeq, int toSeq) {
        for (int seq = fromSeq; seq < toSeq; seq++) {
            int current = seq;
            tx.executeWithoutResult(status -> {
                for (int key = 0; key < KEYS; key++) {
                    writer.append(topic, "key-" + key, "Step", Map.of("seq", current));
                }
            });
        }
    }

    private void drain(OutboxRelay target, String topic, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (unpublished(topic) > 0 && System.nanoTime() < deadline) {
            if (target.pollOnce() == 0) {
                Thread.sleep(200);
            }
        }
    }

    private void waitUntilReachable(String bootstrap) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                TestKafka.createTopic(bootstrap, TestKafka.uniqueTopic("probe"), 1);
                return;
            } catch (Exception e) {
                Thread.sleep(500);
            }
        }
        throw new IllegalStateException("브로커가 다시 응답하지 않는다");
    }

    private OutboxRelay relayTo(String bootstrap) {
        Map<String, Object> producerProperties = new HashMap<>();
        producerProperties.put("bootstrap.servers", bootstrap);
        // 브로커가 없을 때 빨리 실패하게 한다
        producerProperties.put("max.block.ms", "2000");
        producerProperties.put("request.timeout.ms", "2000");
        producerProperties.put("delivery.timeout.ms", "4000");
        KafkaTemplate<String, String> template = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(OutboxProducerSettings.enforce(producerProperties)));
        OutboxProperties.Relay properties = new OutboxProperties.Relay(
                false, Duration.ofMillis(200), 100, Duration.ofSeconds(5), Duration.ofDays(1), Duration.ofMinutes(1));
        return new OutboxRelay(repository, template, transactionManager, properties, new SimpleMeterRegistry());
    }
}
