package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.SmartLifecycle;
import org.springframework.test.context.TestPropertySource;

/**
 * [NFR-4] 자동 시작하는 릴레이가 폴링으로 발행하는지 확인한다. 끝나면 릴레이를 직접 멈춘다. 컨텍스트를 닫으면(@DirtiesContext) 공유 컨테이너도 같이 멈추므로 닫지
 * 않는다.
 */
@TestPropertySource(properties = "curtaincall.outbox.relay.auto-start=true")
class OutboxRelayAutoStartTests extends OutboxIntegrationTest {

    @Autowired
    @Qualifier("outboxRelayLifecycle")
    SmartLifecycle relayLifecycle;

    @AfterEach
    void stopRelay() {
        relayLifecycle.stop();
    }

    @Test
    @DisplayName("자동 시작한 릴레이가 기록된 행을 주기적으로 발행한다")
    void relayRunsOnSchedule() throws Exception {
        String topic = TestKafka.uniqueTopic("relay-auto");
        TestKafka.createTopic(bootstrapServers(), topic, 1);

        tx.executeWithoutResult(status -> writer.append(topic, "ord-1", "OrderCreated", Map.of()));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(unpublished(topic)).isZero());
        assertThat(TestKafka.readAll(bootstrapServers(), topic, 1, Duration.ofSeconds(10)))
                .hasSize(1);
    }
}
