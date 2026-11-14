package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * [EVT-9] event 내부 API(가짜 HTTP 서버)가 컨슈머 백오프보다 오래 실패해도, 복구되면 스케줄러가 재고를 만들고 READY로 바꾼다. 실제 EventClient와 스케줄러를
 * 쓴다. event의 실제 이벤트·API 연결은 별도 이슈에서 확인한다.
 */
@SpringBootTest(
        properties = {
            "curtaincall.inventory.retry-backoff=100ms",
            "curtaincall.inventory.max-backoff=300ms",
            "curtaincall.inventory.scheduler.interval=100ms",
            "curtaincall.event.read-timeout=1s"
        })
@Import({TestContainersConfig.class, SeatInventoryRecoveryTests.Topics.class})
// 스케줄러가 켜진 컨텍스트를 캐시에 남기면 다른 테스트의 PENDING 행을 가져간다
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SeatInventoryRecoveryTests {

    private static final AtomicBoolean EVENT_UP = new AtomicBoolean();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final HttpServer EVENT = startEvent();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @TestConfiguration(proxyBeanMethods = false)
    static class Topics {
        @Bean
        NewTopic sessionTopic() {
            return TopicBuilder.name("event.session").partitions(1).replicas(1).build();
        }
    }

    @DynamicPropertySource
    static void eventUrl(DynamicPropertyRegistry registry) {
        registry.add(
                "curtaincall.event.base-url",
                () -> "http://127.0.0.1:" + EVENT.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        EVENT.stop(0);
    }

    @BeforeEach
    void reset() {
        EVENT_UP.set(false);
        CALLS.set(0);
        jdbc.update("DELETE FROM seat_inventory");
        jdbc.update("DELETE FROM session_stock");
        jdbc.update("DELETE FROM processed_event");
    }

    @Test
    @DisplayName("event가 컨슈머 재시도 시간보다 오래 멈췄다 복구되면 회차가 READY가 된다")
    void convergesAfterLongOutage() throws Exception {
        kafkaTemplate
                .send(
                        "event.session",
                        "7",
                        "{\"eventId\":\"" + UUID.randomUUID()
                                + "\",\"eventType\":\"SessionCreated\",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\","
                                + "\"aggregateId\":\"7\",\"payload\":{\"sessionId\":7}}")
                .get();

        // 8초: 컨슈머 백오프(1s, 2s, 4s) 합보다 길다
        await().atMost(Duration.ofSeconds(15)).until(() -> CALLS.get() >= 3);
        Thread.sleep(8_000);
        assertThat(status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_inventory", Integer.class))
                .isZero();

        EVENT_UP.set(true);

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(status()).isEqualTo("READY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM seat_inventory WHERE session_id = 7", Integer.class))
                .isEqualTo(3);
    }

    private String status() {
        return jdbc.query(
                "SELECT status FROM session_stock WHERE session_id = 7", rs -> rs.next() ? rs.getString(1) : "");
    }

    private static HttpServer startEvent() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body =
                    "[{\"seatId\":1,\"grade\":\"VIP\",\"price\":150000},{\"seatId\":2,\"grade\":\"R\",\"price\":110000},{\"seatId\":3,\"grade\":\"R\",\"price\":110000}]"
                            .getBytes(StandardCharsets.UTF_8);
            server.createContext("/internal/sessions/7/seat-map", exchange -> {
                CALLS.incrementAndGet();
                if (EVENT_UP.get()) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                } else {
                    exchange.sendResponseHeaders(503, -1);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
