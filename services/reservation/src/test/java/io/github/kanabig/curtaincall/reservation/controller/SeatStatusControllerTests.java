package io.github.kanabig.curtaincall.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** RSV-10 좌석 판매 상태 조회. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false",
            "curtaincall.seat-status.cache-ttl=1s"
        })
class SeatStatusControllerTests extends InventoryIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO session_stock (session_id, status, next_attempt_at) VALUES (2, 'PENDING', NOW(6))");
        // 캐시가 테스트 사이에 남으므로 테스트마다 다른 회차(11, 12)를 쓴다
        for (long sessionId : new long[] {11, 12}) {
            jdbc.update(
                    "INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at) VALUES (?, 'READY', 6, NOW(6))",
                    sessionId);
            for (int id = 1; id <= 6; id++) {
                jdbc.update(
                        "INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES (?, ?, 'R', 110000)",
                        sessionId,
                        id);
            }
        }
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> get(long sessionId) {
        return (ResponseEntity<Map<String, Object>>)
                (ResponseEntity<?>) rest.getForEntity("/api/v1/sessions/" + sessionId + "/seat-status", Map.class);
    }

    private void setStatus(long sessionId, long seatId, String status, Instant expiresAt) {
        jdbc.update(
                "UPDATE seat_inventory SET status = ?, hold_id = UUID(), holder_id = 7, expires_at = ?"
                        + " WHERE session_id = ? AND seat_id = ?",
                status,
                expiresAt == null ? null : Timestamp.from(expiresAt),
                sessionId,
                seatId);
    }

    @Test
    @DisplayName("만료 전 점유는 held, 판매는 sold, 만료된 점유와 빈 좌석은 목록에 없다")
    void expiredHoldIsAvailable() {
        setStatus(11, 1, "HELD", Instant.now().plus(Duration.ofMinutes(5)));
        setStatus(11, 2, "HELD", Instant.now().minusSeconds(1));
        setStatus(11, 3, "SOLD", null);

        ResponseEntity<Map<String, Object>> response = get(11);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("held", List.of(1))
                .containsEntry("sold", List.of(3))
                .containsKey("version");
    }

    @Test
    @DisplayName("TTL 안에서는 같은 스냅샷을 돌려주고, TTL이 지나면 바뀐 상태가 보인다")
    void reflectedWithinTtl() {
        ResponseEntity<Map<String, Object>> first = get(12);
        assertThat(first.getBody()).containsEntry("held", List.of()).containsEntry("sold", List.of());

        setStatus(12, 4, "SOLD", null);

        assertThat(get(12).getBody()).isEqualTo(first.getBody());
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(get(12).getBody()).containsEntry("sold", List.of(4)));
    }

    @Test
    @DisplayName("재고가 준비되지 않은 회차와 없는 회차는 409 SESSION_NOT_ON_SALE")
    void notReady() {
        assertThat(get(2).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ResponseEntity<Map<String, Object>> missing = get(999);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(missing.getBody()).containsEntry("code", "SESSION_NOT_ON_SALE");
    }
}
