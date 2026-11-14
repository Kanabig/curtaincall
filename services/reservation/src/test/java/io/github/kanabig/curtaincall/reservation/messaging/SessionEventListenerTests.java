package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** [EVT-9] SessionCreated 소비는 PENDING 상태 행만 남기고 event를 부르지 않는다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
@Import(SessionEventListenerTests.Topics.class)
class SessionEventListenerTests extends InventoryIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic sessionTopic() {
            return TopicBuilder.name("event.session").partitions(1).replicas(1).build();
        }
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 상태 행은 하나이고 event는 부르지 않는다")
    void duplicateEventCreatesOnePendingRow() throws Exception {
        String envelope = sessionCreated(UUID.randomUUID().toString(), 101L);

        kafkaTemplate.send("event.session", "101", envelope).get();
        kafkaTemplate.send("event.session", "101", envelope).get();
        // 뒤에 보낸 다른 회차가 처리되면 앞의 두 레코드도 소비가 끝난 것이다
        kafkaTemplate
                .send("event.session", "102", sessionCreated(UUID.randomUUID().toString(), 102L))
                .get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statuses()).isEqualTo(2));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM session_stock WHERE session_id = 101 AND status = 'PENDING'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(inventoryCount(101L)).isZero();
        verifyNoInteractions(eventClient);
    }

    @Test
    @DisplayName("SessionCreated가 아닌 회차 이벤트는 상태 행을 만들지 않는다")
    void ignoresOtherSessionEvents() throws Exception {
        String suspended = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventType\":\"SessionSuspended\",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\","
                + "\"aggregateId\":\"301\",\"payload\":{\"sessionId\":301}}";
        kafkaTemplate.send("event.session", "301", suspended).get();
        kafkaTemplate
                .send("event.session", "302", sessionCreated(UUID.randomUUID().toString(), 302L))
                .get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(statuses()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM session_stock WHERE session_id = 301", Integer.class))
                .isZero();
    }

    private int statuses() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM session_stock", Integer.class);
    }

    private static String sessionCreated(String eventId, long sessionId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"SessionCreated\","
                + "\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\",\"aggregateId\":\"" + sessionId
                + "\",\"payload\":{\"sessionId\":" + sessionId + "}}";
    }
}
