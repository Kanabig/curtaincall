package io.github.kanabig.curtaincall.reservation.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import io.github.kanabig.curtaincall.reservation.service.ReservationService;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.Acknowledgment;

/** [RSV-9] 회차 이벤트로 받은 시작 시각이 session_stock에 저장되고 취소 기한 계산에 쓰이는지 확인한다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class SessionStartAtTests extends OrderIntegrationTest {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    SessionEventListener listener;

    @Autowired
    ReservationService reservationService;

    private long offset;

    @Test
    @DisplayName("SessionCreated의 startAt이 저장되고 그 날짜 00:00(KST)이 취소 기한이 된다")
    void createdStoresStartAt() {
        LocalDate today = LocalDate.now(KST);
        String orderId = confirmedOrder();

        deliver("SessionCreated", startAt(today, "19:30:00+09:00"));

        assertThat(storedStartAt())
                .isEqualTo(OffsetDateTime.parse(today + "T19:30:00+09:00").toInstant());
        assertThatThrownBy(() -> reservationService.cancel(USER, reservationId(orderId), key()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("CANCEL_DEADLINE_PASSED"));
    }

    @Test
    @DisplayName("SessionUpdated로 시작 시각이 바뀌면 새 기한이 적용된다")
    void updatedMovesDeadline() {
        LocalDate today = LocalDate.now(KST);
        String orderId = confirmedOrder();
        deliver("SessionCreated", startAt(today, "19:30:00+09:00"));

        deliver("SessionUpdated", startAt(today.plusDays(1), "19:30:00+09:00"));

        assertThat(storedStartAt())
                .isEqualTo(OffsetDateTime.parse(today.plusDays(1) + "T19:30:00+09:00")
                        .toInstant());
        assertThatCode(() -> reservationService.cancel(USER, reservationId(orderId), key()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("같은 회차의 SessionCreated가 다시 와도 갱신된 시작 시각을 덮어쓰지 않는다")
    void redeliveredCreatedKeepsUpdatedValue() {
        LocalDate today = LocalDate.now(KST);
        deliver("SessionCreated", startAt(today, "19:30:00+09:00"));
        deliver("SessionUpdated", startAt(today.plusDays(3), "20:00:00+09:00"));

        deliver("SessionCreated", startAt(today, "19:30:00+09:00"));

        assertThat(storedStartAt())
                .isEqualTo(OffsetDateTime.parse(today.plusDays(3) + "T20:00:00+09:00")
                        .toInstant());
    }

    @Test
    @DisplayName("startAt이 없는 SessionUpdated는 저장된 값을 그대로 둔다")
    void updatedWithoutStartAtIgnored() {
        LocalDate today = LocalDate.now(KST);
        deliver("SessionCreated", startAt(today.plusDays(2), "19:30:00+09:00"));
        Instant before = storedStartAt();

        deliver("SessionUpdated", "{\"sessionId\":" + SESSION + ",\"saleOpenAt\":\"2026-12-01T10:00:00+09:00\"}");

        assertThat(storedStartAt()).isEqualTo(before);
    }

    private String confirmedOrder() {
        String orderId = orderInPayment(USER, 1L);
        approved(orderId);
        return orderId;
    }

    private String reservationId(String orderId) {
        return jdbc.queryForObject("SELECT reservation_id FROM reservation WHERE order_id = ?", String.class, orderId);
    }

    private Instant storedStartAt() {
        Timestamp ts = jdbc.queryForObject(
                "SELECT start_at FROM session_stock WHERE session_id = ?", Timestamp.class, SESSION);
        return ts.toInstant();
    }

    private static String startAt(LocalDate date, String timeWithOffset) {
        return "{\"sessionId\":" + SESSION + ",\"startAt\":\"" + date + "T" + timeWithOffset + "\"}";
    }

    private void deliver(String eventType, String payload) {
        String value = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + eventType
                + "\",\"occurredAt\":\"2026-12-01T20:03:11.120+09:00\",\"aggregateId\":\"" + SESSION
                + "\",\"payload\":" + payload + "}";
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("event.session", 0, offset++, String.valueOf(SESSION), value);
        listener.onSessionEvent(record, org.mockito.Mockito.mock(Acknowledgment.class));
    }
}
