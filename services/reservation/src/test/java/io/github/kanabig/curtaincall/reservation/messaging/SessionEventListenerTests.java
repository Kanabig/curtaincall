package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/** [EVT-9] SessionCreated 소비로 재고가 만들어지는 흐름. */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=true")
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
    @DisplayName("같은 이벤트가 두 번 와도 event는 한 번만 호출하고 재고 수는 그대로다")
    void duplicateEventCreatesInventoryOnce() throws Exception {
        when(eventClient.fetchSeatMap(101L)).thenReturn(seatMap(30));
        String envelope = sessionCreated(UUID.randomUUID().toString(), 101L);

        kafkaTemplate.send("event.session", "101", envelope).get();
        kafkaTemplate.send("event.session", "101", envelope).get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(inventoryCount(101L)).isEqualTo(30));
        // 두 번째 레코드까지 소비한 뒤에 호출 횟수를 확인한다
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_event", Integer.class))
                                .isEqualTo(1));
        kafkaTemplate
                .send("event.session", "102", sessionCreated(UUID.randomUUID().toString(), 102L))
                .get();
        when(eventClient.fetchSeatMap(102L)).thenReturn(seatMap(5));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(inventoryCount(102L)).isEqualTo(5));

        verify(eventClient, times(1)).fetchSeatMap(101L);
        assertThat(inventoryCount(101L)).isEqualTo(30);
    }

    @Test
    @DisplayName("event 호출이 한 번 실패해도 재시도로 재고가 만들어진다")
    void retriesAfterEventFailure() throws Exception {
        when(eventClient.fetchSeatMap(201L))
                .thenThrow(new IllegalStateException("event 일시 중단"))
                .thenReturn(seatMap(20));

        kafkaTemplate
                .send("event.session", "201", sessionCreated(UUID.randomUUID().toString(), 201L))
                .get();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(inventoryCount(201L)).isEqualTo(20));
        verify(eventClient, times(2)).fetchSeatMap(201L);
    }

    @Test
    @DisplayName("SessionCreated가 아닌 회차 이벤트는 재고를 만들지 않는다")
    void ignoresOtherSessionEvents() throws Exception {
        String suspended = "{\"eventId\":\"" + UUID.randomUUID()
                + "\",\"eventType\":\"SessionSuspended\",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\","
                + "\"aggregateId\":\"301\",\"payload\":{\"sessionId\":301}}";
        kafkaTemplate.send("event.session", "301", suspended).get();
        kafkaTemplate
                .send("event.session", "302", sessionCreated(UUID.randomUUID().toString(), 302L))
                .get();
        when(eventClient.fetchSeatMap(302L)).thenReturn(seatMap(3));

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(inventoryCount(302L)).isEqualTo(3));
        assertThat(inventoryCount(301L)).isZero();
        verify(eventClient, times(0)).fetchSeatMap(301L);
    }

    private static String sessionCreated(String eventId, long sessionId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"SessionCreated\","
                + "\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\",\"aggregateId\":\"" + sessionId
                + "\",\"payload\":{\"sessionId\":" + sessionId + "}}";
    }
}
