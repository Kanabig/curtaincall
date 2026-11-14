package io.github.kanabig.curtaincall.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** RSV-8 내 예매 목록·상세. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class ReservationQueryControllerTests extends OrderIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-11-01T00:00:00Z");

    @Autowired
    TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> get(String path, long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        return (ResponseEntity<Map<String, Object>>)
                (ResponseEntity<?>) rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    List<String> ids(ResponseEntity<Map<String, Object>> response) {
        return ((List<Map<String, Object>>) response.getBody().get("items"))
                .stream().map(item -> (String) item.get("reservationId")).toList();
    }

    void insert(String id, long userId, String status, Instant createdAt) {
        jdbc.update(
                "INSERT INTO reservation (reservation_id, order_id, user_id, session_id, seat_ids, amount, status,"
                        + " created_at) VALUES (?, ?, ?, ?, '[1, 2]', 220000, ?, ?)",
                id,
                "ord_" + id,
                userId,
                SESSION,
                status,
                Timestamp.from(createdAt));
    }

    @Test
    @DisplayName("예매 일시 내림차순이고 같은 시각이면 reservation_id 내림차순이다. 남의 예매는 섞이지 않는다")
    void sortedByCreatedAtDesc() {
        insert("rsv_a", USER, "CONFIRMED", BASE);
        insert("rsv_b", USER, "CANCELLED", BASE.plus(2, ChronoUnit.HOURS));
        insert("rsv_c", USER, "CONFIRMED", BASE.plus(2, ChronoUnit.HOURS));
        insert("rsv_x", USER + 1, "CONFIRMED", BASE.plus(5, ChronoUnit.HOURS));

        var response = get("/api/v1/reservations", USER);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(response)).containsExactly("rsv_c", "rsv_b", "rsv_a");
        assertThat(response.getBody()).containsEntry("totalElements", 3).containsEntry("size", 20);
    }

    @Test
    @DisplayName("페이지 경계: 마지막 쪽은 남은 만큼만, 범위를 넘으면 빈 목록, total은 유지된다")
    void pageBoundaries() {
        for (int i = 0; i < 5; i++) {
            insert("rsv_" + i, USER, "CONFIRMED", BASE.plus(i, ChronoUnit.MINUTES));
        }

        assertThat(ids(get("/api/v1/reservations?size=2&page=0", USER))).containsExactly("rsv_4", "rsv_3");
        assertThat(ids(get("/api/v1/reservations?size=2&page=2", USER))).containsExactly("rsv_0");
        var beyond = get("/api/v1/reservations?size=2&page=3", USER);
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.getBody()).containsEntry("totalElements", 5).containsEntry("totalPages", 3);
        assertThat(get("/api/v1/reservations", USER + 9).getBody())
                .containsEntry("totalElements", 0)
                .containsEntry("totalPages", 0);
    }

    @Test
    @DisplayName("status 필터는 해당 상태만 남기고, size 50은 허용, 51·0, page -1, 모르는 status는 400이다")
    void filterAndLimits() {
        insert("rsv_a", USER, "CONFIRMED", BASE);
        insert("rsv_b", USER, "CANCEL_REQUESTED", BASE.plus(1, ChronoUnit.MINUTES));
        insert("rsv_c", USER, "CANCELLED", BASE.plus(2, ChronoUnit.MINUTES));

        assertThat(ids(get("/api/v1/reservations?status=CANCELLED", USER))).containsExactly("rsv_c");
        assertThat(get("/api/v1/reservations?status=CANCELLED", USER).getBody()).containsEntry("totalElements", 1);
        assertThat(get("/api/v1/reservations?size=50", USER).getStatusCode()).isEqualTo(HttpStatus.OK);
        for (String bad : List.of("size=51", "size=0", "page=-1", "status=NOPE", "size=abc")) {
            var response = get("/api/v1/reservations?" + bad, USER);
            assertThat(response.getStatusCode()).as(bad).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("code", "INVALID_REQUEST");
        }
    }

    @Test
    @DisplayName("결제 중인 주문은 목록에 없고, 확정되면 나타난다. 회차 시각이 없으면 sessionStartAt은 null이다")
    void onlyConfirmedReservations() {
        String orderId = orderInPayment(USER, 1L, 2L);
        assertThat(get("/api/v1/reservations", USER).getBody()).containsEntry("totalElements", 0);

        approved(orderId);

        var response = get("/api/v1/reservations", USER);
        assertThat(response.getBody()).containsEntry("totalElements", 1);
        @SuppressWarnings("unchecked")
        var item = ((List<Map<String, Object>>) response.getBody().get("items")).get(0);
        assertThat(item)
                .containsEntry("status", "CONFIRMED")
                .containsEntry("sessionId", (int) SESSION)
                .containsEntry("amount", 220000)
                .containsEntry("seatIds", List.of(1, 2))
                .containsEntry("sessionStartAt", null);
    }

    @Test
    @DisplayName("상세는 좌석별 등급·가격과 회차 시각을 담고, 남의 예매·없는 예매는 같은 404 RESERVATION_NOT_FOUND다")
    void detailAndOwnership() {
        Instant startAt = Instant.parse("2026-12-24T10:00:00Z");
        jdbc.update("UPDATE session_stock SET start_at = ? WHERE session_id = ?", Timestamp.from(startAt), SESSION);
        String orderId = orderInPayment(USER, 3L, 4L);
        approved(orderId);
        String reservationId =
                jdbc.queryForObject("SELECT reservation_id FROM reservation WHERE order_id = ?", String.class, orderId);

        var mine = get("/api/v1/reservations/" + reservationId, USER);
        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody())
                .containsEntry("orderId", orderId)
                .containsEntry("sessionStartAt", "2026-12-24T10:00:00Z")
                .containsEntry("status", "CONFIRMED");
        assertThat(mine.getBody().get("seats"))
                .isEqualTo(List.of(
                        Map.of("seatId", 3, "grade", "R", "price", 110000),
                        Map.of("seatId", 4, "grade", "R", "price", 110000)));

        var others = get("/api/v1/reservations/" + reservationId, USER + 1);
        var missing = get("/api/v1/reservations/rsv_none", USER);
        assertThat(others.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(others.getBody()).containsEntry("code", "RESERVATION_NOT_FOUND");
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "RESERVATION_NOT_FOUND");
        assertThat(others.getBody().get("detail")).isEqualTo(missing.getBody().get("detail"));
    }
}
