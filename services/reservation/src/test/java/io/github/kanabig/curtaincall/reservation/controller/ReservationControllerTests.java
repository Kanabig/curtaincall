package io.github.kanabig.curtaincall.reservation.controller;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** RSV-9 예매 취소 API. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class ReservationControllerTests extends OrderIntegrationTest {

    @Autowired
    TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> cancel(String reservationId, long userId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return (ResponseEntity<Map<String, Object>>) (ResponseEntity<?>) rest.postForEntity(
                "/api/v1/reservations/" + reservationId + "/cancel", new HttpEntity<>(headers), Map.class);
    }

    @Test
    @DisplayName("취소는 202 CANCEL_REQUESTED, 같은 키 재요청은 같은 응답이고 Idempotency-Key가 없으면 400이다")
    void cancelFlow() {
        String reservationId = confirmedReservation(Instant.now().plus(30, ChronoUnit.DAYS));
        String key = key();

        var accepted = cancel(reservationId, USER, key);
        var replay = cancel(reservationId, USER, key);

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(accepted.getBody()).containsEntry("status", "CANCEL_REQUESTED");
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).isEqualTo(accepted.getBody());
        assertThat(cancel(reservationId, USER, null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cancel(reservationId, USER, "not-a-uuid").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cancel(reservationId, USER + 1, key()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("공연일이 되면 409 CANCEL_DEADLINE_PASSED")
    void deadlinePassed() {
        String reservationId = confirmedReservation(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))
                .atTime(23, 0)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant());

        var rejected = cancel(reservationId, USER, key());

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rejected.getBody()).containsEntry("code", "CANCEL_DEADLINE_PASSED");
    }

    private String confirmedReservation(Instant startAt) {
        jdbc.update("UPDATE session_stock SET start_at = ? WHERE session_id = ?", Timestamp.from(startAt), SESSION);
        String orderId = orderInPayment(USER, 1L);
        approved(orderId);
        return jdbc.queryForObject("SELECT reservation_id FROM reservation WHERE order_id = ?", String.class, orderId);
    }
}
