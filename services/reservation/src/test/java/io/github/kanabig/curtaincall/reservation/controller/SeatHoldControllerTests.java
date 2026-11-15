package io.github.kanabig.curtaincall.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** RSV-1~4 점유 API와 재고 생성 지표. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class SeatHoldControllerTests extends InventoryIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    MeterRegistry meterRegistry;

    static final Long NO_HEADER = -1L;

    /** null이면 요청 회차와 같은 값을 보내고, {@link #NO_HEADER}면 헤더를 보내지 않는다. */
    Long entryHeader;

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at) VALUES (1, 'READY', 10, NOW(6))");
        jdbc.update("INSERT INTO session_stock (session_id, status, next_attempt_at) VALUES (2, 'PENDING', NOW(6))");
        for (int id = 1; id <= 10; id++) {
            jdbc.update(
                    "INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES (1, ?, 'R', 110000)", id);
        }
    }

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> post(long sessionId, long userId, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-Id", String.valueOf(userId));
        if (entryHeader != NO_HEADER) {
            headers.set("X-Entry-Session-Id", String.valueOf(entryHeader == null ? sessionId : entryHeader));
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) rest.exchange(
                "/api/v1/sessions/" + sessionId + "/holds",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    @Test
    @DisplayName("점유는 201과 holdId·seatIds·expiresAt을 돌려주고, 같은 좌석을 다시 요청하면 409 SEAT_ALREADY_HELD와 막힌 좌석을 돌려준다")
    void holdAndConflict() {
        ResponseEntity<Map<String, Object>> created = post(1, 100, Map.of("seatIds", List.of(2, 1)));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsKeys("holdId", "expiresAt").containsEntry("seatIds", List.of(1, 2));

        ResponseEntity<Map<String, Object>> conflict = post(1, 200, Map.of("seatIds", List.of(2, 3)));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody())
                .containsEntry("code", "SEAT_ALREADY_HELD")
                .containsEntry("seatIds", List.of(2));
    }

    @Test
    @DisplayName("[QUE-5] 입장 토큰의 회차가 요청 회차와 다르거나 입장 정보가 없으면 403 ENTRY_TOKEN_INVALID이고 좌석은 잡히지 않는다")
    void entrySessionMismatch() {
        entryHeader = 2L;
        ResponseEntity<Map<String, Object>> other = post(1, 100, Map.of("seatIds", List.of(1)));
        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(other.getBody()).containsEntry("code", "ENTRY_TOKEN_INVALID");

        entryHeader = NO_HEADER;
        ResponseEntity<Map<String, Object>> missing = post(1, 100, Map.of("seatIds", List.of(1)));
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(missing.getBody()).containsEntry("code", "ENTRY_TOKEN_INVALID");

        entryHeader = null;
        assertThat(post(1, 200, Map.of("seatIds", List.of(1))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("한도 초과는 409 PURCHASE_LIMIT_EXCEEDED, 5개 요청과 빈 요청은 400, 준비되지 않은 회차는 409 SESSION_NOT_ON_SALE")
    void errors() {
        post(1, 100, Map.of("seatIds", List.of(1, 2, 3)));

        assertThat(post(1, 100, Map.of("seatIds", List.of(4, 5))).getBody())
                .containsEntry("code", "PURCHASE_LIMIT_EXCEEDED");
        assertThat(post(1, 300, Map.of("seatIds", List.of(1, 2, 3, 4, 5))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(post(1, 300, Map.of("seatIds", List.of())).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<Map<String, Object>> notReady = post(2, 300, Map.of("seatIds", List.of(1)));
        assertThat(notReady.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notReady.getBody()).containsEntry("code", "SESSION_NOT_ON_SALE");
    }

    @Test
    @DisplayName("DELETE는 본인 점유를 풀면 204, 다시 풀거나 남의 점유면 404")
    void release() {
        String holdId =
                (String) post(1, 100, Map.of("seatIds", List.of(1))).getBody().get("holdId");

        assertThat(delete(holdId, 999).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(holdId, 100).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(holdId, 100).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    ResponseEntity<String> delete(String holdId, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return rest.exchange("/api/v1/holds/" + holdId, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("PENDING 회차 수와 가장 오래된 PENDING의 대기 시간이 지표로 나온다")
    void pendingStockMetrics() throws Exception {
        jdbc.update("UPDATE session_stock SET created_at = NOW(6) - INTERVAL 90 SECOND WHERE session_id = 2");

        assertThat(meterRegistry.get("seat.stock.pending").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("seat.stock.pending.oldest.age").gauge().value())
                .isBetween(89.0, 100.0);
    }
}
