package io.github.kanabig.curtaincall.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.notification.FakeSmtp.Mail;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** NTF-1~3: Kafka를 거친 메일 발송, 중복 소비, SMTP·user 장애. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({TestContainersConfig.class, OrderEventMailTests.Topics.class})
class OrderEventMailTests {

    static final FakeSmtp SMTP = new FakeSmtp();
    static final FakeUser USER = new FakeUser();

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic orderTopic() {
            return TopicBuilder.name("reservation.order")
                    .partitions(3)
                    .replicas(1)
                    .build();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.port", SMTP::port);
        registry.add("curtaincall.user.base-url", USER::baseUrl);
        registry.add("spring.mail.properties.mail.smtp.timeout", () -> 2000);
    }

    @AfterAll
    static void stop() throws Exception {
        SMTP.close();
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaConnectionDetails connectionDetails;

    @BeforeEach
    void reset() {
        SMTP.reset();
        USER.reset();
        jdbc.update("DELETE FROM processed_event");
    }

    private String confirmed(String eventId, long userId, String orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"ReservationConfirmed\","
                + "\"occurredAt\":\"2026-11-15T10:00:00.000+09:00\",\"aggregateId\":\"" + orderId + "\","
                + "\"payload\":{\"reservationId\":\"rsv_1\",\"orderId\":\"" + orderId + "\",\"userId\":" + userId
                + ",\"sessionId\":1,\"seatIds\":[\"A-1\",\"A-2\"],\"amount\":120000}}";
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 메일은 한 통이다 (NTF-2)")
    void duplicateEventSendsOneMail() throws Exception {
        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String message = confirmed(UUID.randomUUID().toString(), 7, orderId);
        kafka.send("reservation.order", orderId, message).get();
        kafka.send("reservation.order", orderId, message).get();
        // 순서상 뒤따르는 다른 이벤트까지 처리되면 앞의 중복도 처리된 것이다
        String otherOrder = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        kafka.send("reservation.order", orderId, confirmed(UUID.randomUUID().toString(), 8, otherOrder))
                .get();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(SMTP.mails()).hasSize(2));
        List<Mail> mails = SMTP.mails();
        assertThat(mails).extracting(Mail::to).containsExactlyInAnyOrder("user7@example.com", "user8@example.com");
        Mail first = mails.stream()
                .filter(m -> m.to().equals("user7@example.com"))
                .findFirst()
                .orElseThrow();
        assertThat(first.decoded()).contains("rsv_1", orderId, "A-1, A-2");
    }

    @Test
    @DisplayName("SMTP가 계속 실패하면 재시도 뒤 DLT로 가고 메일 기록은 남지 않는다 (NTF-3)")
    void smtpOutageGoesToDlt() throws Exception {
        SMTP.fail = true;
        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String eventId = UUID.randomUUID().toString();
        kafka.send("reservation.order", orderId, confirmed(eventId, 9, orderId)).get();

        ConsumerRecord<String, String> dead = awaitDlt(orderId);
        assertThat(dead.value()).contains(eventId);
        assertThat(SMTP.mails()).isEmpty();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM processed_event WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    @Test
    @DisplayName("user가 잠깐 실패해도 재시도로 복구되어 메일은 한 통이다")
    void userOutageRecovers() throws Exception {
        USER.failures.set(2);
        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        kafka.send("reservation.order", orderId, confirmed(UUID.randomUUID().toString(), 11, orderId))
                .get();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(SMTP.mails()).hasSize(1));
        assertThat(SMTP.mails().get(0).to()).isEqualTo("user11@example.com");
        assertThat(USER.requests.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("취소·환불 이벤트도 각각 메일이 나간다 (NTF-1)")
    void cancelAndRefundSendMail() throws Exception {
        String orderId = "ord_" + UUID.randomUUID().toString().substring(0, 8);
        String cancelled = confirmed(UUID.randomUUID().toString(), 12, orderId)
                .replace("ReservationConfirmed", "ReservationCancelled");
        String refunded = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"OrderRefunded\","
                + "\"occurredAt\":\"2026-11-15T10:00:00.000+09:00\",\"aggregateId\":\"" + orderId + "\","
                + "\"payload\":{\"orderId\":\"" + orderId + "\",\"userId\":13,\"sessionId\":1,\"amount\":50000}}";
        kafka.send("reservation.order", orderId, cancelled).get();
        kafka.send("reservation.order", orderId, refunded).get();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(SMTP.mails()).hasSize(2));
        assertThat(SMTP.mails())
                .extracting(Mail::decoded)
                .anyMatch(d -> d.contains("취소"))
                .anyMatch(d -> d.contains("50,000"));
    }

    private ConsumerRecord<String, String> awaitDlt(String key) {
        Properties props = new Properties();
        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                connectionDetails.getConsumer().getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("reservation.order.notification.DLT"));
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                return !found.isEmpty();
            });
            return found.get(0);
        }
    }
}
