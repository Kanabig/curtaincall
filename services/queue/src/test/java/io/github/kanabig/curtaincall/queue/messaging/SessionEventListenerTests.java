package io.github.kanabig.curtaincall.queue.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.repository.SessionConf;
import io.github.kanabig.curtaincall.queue.repository.SessionConf.Type;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
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

@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=true",
            "curtaincall.queue.scheduling.enabled=false",
            "curtaincall.jwt.entry-secret=" + QueueIntegrationTest.ENTRY_SECRET
        })
@Import({TestContainersConfig.class, SessionEventListenerTests.Topics.class})
class SessionEventListenerTests extends QueueIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    SessionEventListener listener;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic sessionTopic() {
            return TopicBuilder.name("event.session").partitions(1).replicas(1).build();
        }
    }

    private static String envelope(String type, long sid, String occurredAt, String payload) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","aggregateId":"%d","payload":%s}""".formatted(UUID.randomUUID(), type, occurredAt, sid, payload);
    }

    private String created(String occurredAt, int admission) {
        return envelope("SessionCreated", sessionId, occurredAt, """
                {"sessionId":%d,"performanceId":1,"venueId":"FC1","startAt":"2027-01-01T20:00:00+09:00",
                 "saleOpenAt":"2026-12-01T20:00:00+09:00","prices":[{"grade":"R","price":100000}],
                 "admissionPerSecond":%d,"maxActiveUsers":300}""".formatted(sessionId, admission));
    }

    @Test
    @DisplayName("[QUE-1] 회차 이벤트를 소비하면 conf에 오픈 시각과 입장 설정이 들어가고, 같은 메시지가 두 번 와도 같다")
    void consumesAndDedups() {
        String message = created("2026-11-15T10:00:00.000+09:00", 50);

        kafka.send("event.session", String.valueOf(sessionId), message);
        kafka.send("event.session", String.valueOf(sessionId), message);
        kafka.send("event.session", String.valueOf(sessionId), created("2026-11-15T10:00:05.000+09:00", 80));
        kafka.send("event.session", String.valueOf(sessionId), message);

        String key = "q:" + sessionId + ":conf";
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(redis.opsForHash().get(key, "admissionPerSecond")).isEqualTo("80");
        });
        // 마지막에 다시 온 옛 메시지까지 소비된 뒤에도 값이 바뀌지 않는다
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(redis.opsForHash().get(key, "admissionPerSecond")).isEqualTo("80");
        });
        assertThat(redis.opsForHash().get(key, "saleOpenAt")).isEqualTo("1796122800000");
        assertThat(redis.opsForHash().get(key, "maxActiveUsers")).isEqualTo("300");
    }

    @Test
    @DisplayName("SessionSuspended를 소비하면 판매 중 집합에서 빠지고 상태가 SUSPENDED가 된다")
    void consumesSuspended() {
        kafka.send("event.session", String.valueOf(sessionId), created("2026-11-15T10:00:00.000+09:00", 50));
        kafka.send(
                "event.session",
                String.valueOf(sessionId),
                envelope(
                        "SessionSuspended",
                        sessionId,
                        "2026-11-15T10:00:01.000+09:00",
                        "{\"sessionId\":" + sessionId + "}"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(redis.opsForHash().get("q:" + sessionId + ":conf", "status"))
                    .isEqualTo("SUSPENDED");
            assertThat(redis.opsForSet().isMember("q:open", String.valueOf(sessionId)))
                    .isFalse();
        });
    }

    @Test
    @DisplayName("봉투를 읽을 수 없거나 설정 값이 잘못된 이벤트는 재시도 없는 예외로 처리한다")
    void invalidEventsAreNotRetryable() {
        assertThatThrownBy(() -> listener.parse("not json")).isInstanceOf(InvalidSessionEventException.class);
        assertThatThrownBy(() -> listener.parse("{\"eventType\":\"SessionCreated\"}"))
                .isInstanceOf(InvalidSessionEventException.class);
        String zeroAdmission = created("2026-11-15T10:00:00.000+09:00", 0);
        assertThatThrownBy(() -> listener.parse(zeroAdmission)).isInstanceOf(InvalidSessionEventException.class);
    }

    @Test
    @DisplayName("대기열이 쓰지 않는 이벤트 타입은 무시하고, 생성 이벤트의 발생 시각 ms를 버전으로 쓴다")
    void parse() {
        assertThat(listener.parse(envelope("SomethingElse", 1, "2026-11-15T10:00:00.000+09:00", "{}")))
                .isNull();
        SessionConf conf = listener.parse(created("2026-11-15T10:00:00.123+09:00", 50));
        assertThat(conf.type()).isEqualTo(Type.UPSERT);
        assertThat(conf.version()).isEqualTo(1_794_704_400_123L);
    }
}
