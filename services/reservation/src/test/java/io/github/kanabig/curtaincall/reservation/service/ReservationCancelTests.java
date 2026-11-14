package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import io.github.kanabig.curtaincall.reservation.dto.CancelResponse;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** RSV-9 예매 취소: 기한 경계, 환불 전 좌석 유지, 중복 요청, 환불 뒤 좌석 해제. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class ReservationCancelTests extends OrderIntegrationTest {

    static final int REPEAT = 20;
    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 공연은 2026-12-01 19:00 KST. 기한은 2026-11-30 23:59:59까지다. */
    static final ZonedDateTime START = ZonedDateTime.of(2026, 12, 1, 19, 0, 0, 0, KST);

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        volatile Instant now = Instant.now();

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Autowired
    MutableClock clock;

    @Autowired
    ReservationService reservationService;

    @BeforeEach
    void setStart() {
        // 점유는 시스템 시계로 만료를 계산하므로 경계 테스트 밖에서는 실제 시각을 쓴다
        clock.now = Instant.now();
        jdbc.update(
                "UPDATE session_stock SET start_at = ? WHERE session_id = ?",
                java.sql.Timestamp.from(START.toInstant()),
                SESSION);
    }

    @Test
    @DisplayName("공연 전날 23:59:59까지는 취소되고, 공연일 00:00부터는 409로 아무것도 바뀌지 않는다")
    void deadlineBoundary() {
        String before = confirmed(USER, 1L);
        String after = confirmed(USER + 1, 2L);

        clock.now = START.toLocalDate().atStartOfDay(KST).minusSeconds(1).toInstant();
        assertThat(reservationService.cancel(USER, reservationId(before), key()).status())
                .isEqualTo(ReservationStatus.CANCEL_REQUESTED);

        clock.now = START.toLocalDate().atStartOfDay(KST).toInstant();
        assertThatThrownBy(() -> reservationService.cancel(USER + 1, reservationId(after), key()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("CANCEL_DEADLINE_PASSED"));
        assertThat(reservationStatus(after)).isEqualTo("CONFIRMED");
        assertThat(outboxTypes(after)).containsExactly("ApprovePayment", "ReservationConfirmed");
    }

    @Test
    @DisplayName("환불이 끝나기 전에는 좌석이 SOLD로 남아 다른 사용자가 점유할 수 없다")
    void seatsStaySoldUntilRefund() throws Exception {
        String orderId = confirmed(USER, 1L, 2L);

        var response = reservationService.cancel(USER, reservationId(orderId), key());

        assertThat(response.status()).isEqualTo(ReservationStatus.CANCEL_REQUESTED);
        assertThat(reservationStatus(orderId)).isEqualTo("CANCEL_REQUESTED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(2);
        assertThatThrownBy(() -> hold(USER + 1, 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("SEAT_ALREADY_HELD"));
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed", "CancelPayment");
        var cancel = outboxPayload(orderId, "CancelPayment");
        assertThat(cancel.get("reason").asText()).isEqualTo("USER_CANCEL");
        assertThat(cancel.get("paymentId").asText()).isEqualTo("pay_" + orderId);
        assertThat(cancel.get("amount").asLong()).isEqualTo(220_000);
    }

    @Test
    @DisplayName("환불 완료를 받으면 좌석이 풀리고 CANCELLED, ReservationCancelled가 남으며 다른 사용자가 점유·구매할 수 있다")
    void refundReleasesSeats() throws Exception {
        String orderId = confirmed(USER, 1L, 2L);
        reservationService.cancel(USER, reservationId(orderId), key());

        deliver("PaymentCancelled", orderId, Map.of("amount", 220_000, "reason", "USER_CANCEL"));

        assertThat(reservationStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(seatsWithStatus("AVAILABLE")).isEqualTo(20);
        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(outboxTypes(orderId))
                .containsExactly("ApprovePayment", "ReservationConfirmed", "CancelPayment", "ReservationCancelled");
        var cancelled = outboxPayload(orderId, "ReservationCancelled");
        assertThat(cancelled.get("reservationId").asText()).isEqualTo(reservationId(orderId));
        assertThat(cancelled.get("seatIds")).hasSize(2);
        assertThat(cancelled.get("amount").asLong()).isEqualTo(220_000);

        // 다른 사용자가 같은 좌석을 점유하고 결제까지 확정한다
        String other = confirmed(USER + 1, 1L, 2L);
        assertThat(reservationStatus(other)).isEqualTo("CONFIRMED");
        // 취소한 사용자의 매수 한도도 돌아온다
        assertThat(hold(USER, 3L, 4L, 5L, 6L).seatIds()).hasSize(4);
    }

    @Test
    @DisplayName("같은 키·다른 키로 취소를 반복하고 환불 완료가 중복돼도 환불 명령과 완료 이벤트는 하나다")
    void duplicates() {
        String orderId = confirmed(USER, 1L);
        String reservationId = reservationId(orderId);
        String key = key();

        CancelResponse first = reservationService.cancel(USER, reservationId, key);
        CancelResponse replay = reservationService.cancel(USER, reservationId, key);
        CancelResponse otherKey = reservationService.cancel(USER, reservationId, key());

        assertThat(replay).isEqualTo(first);
        assertThat(otherKey.status()).isEqualTo(ReservationStatus.CANCEL_REQUESTED);
        assertThat(cancelCommands(orderId)).isEqualTo(1);

        deliver("PaymentCancelled", orderId, Map.of("reason", "USER_CANCEL"));
        deliver("PaymentCancelled", orderId, Map.of("reason", "USER_CANCEL"));
        CancelResponse afterRefund = reservationService.cancel(USER, reservationId, key);
        CancelResponse afterRefundNewKey = reservationService.cancel(USER, reservationId, key());

        assertThat(afterRefund).isEqualTo(first);
        assertThat(afterRefundNewKey.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelCommands(orderId)).isEqualTo(1);
        assertThat(outboxTypes(orderId).stream().filter("ReservationCancelled"::equals))
                .hasSize(1);
        assertThat(seatsWithStatus("AVAILABLE")).isEqualTo(20);
    }

    @RepeatedTest(REPEAT)
    @DisplayName("같은 예매를 동시에 여러 번 취소해도 환불 명령은 하나다")
    void concurrentCancel() throws Exception {
        String orderId = confirmed(USER, 1L, 2L);
        String reservationId = reservationId(orderId);
        List<java.util.concurrent.Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String key = i < 4 ? "00000000-0000-0000-0000-000000000001" : key();
            tasks.add(() -> reservationService.cancel(USER, reservationId, key));
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isInstanceOf(CancelResponse.class));
        assertThat(cancelCommands(orderId)).isEqualTo(1);
        assertThat(seatsWithStatus("SOLD")).isEqualTo(2);
    }

    @RepeatedTest(REPEAT)
    @DisplayName("환불 완료가 동시에 여러 번 와도 좌석 해제와 완료 이벤트는 한 번이다")
    void concurrentRefundCompletion() throws Exception {
        String orderId = confirmed(USER, 1L, 2L);
        reservationService.cancel(USER, reservationId(orderId), key());
        List<java.util.concurrent.Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                deliver("PaymentCancelled", orderId, Map.of("reason", "USER_CANCEL"));
                return null;
            });
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isNull());
        assertThat(reservationStatus(orderId)).isEqualTo("CANCELLED");
        assertThat(seatsWithStatus("AVAILABLE")).isEqualTo(20);
        assertThat(outboxTypes(orderId).stream().filter("ReservationCancelled"::equals))
                .hasSize(1);
    }

    @Test
    @DisplayName("환불 완료 전에 환불 완료가 먼저 오거나 취소하지 않은 예매에 오면 무시한다")
    void unexpectedRefundIgnored() {
        String orderId = confirmed(USER, 1L);

        deliver("PaymentCancelled", orderId, Map.of("reason", "USER_CANCEL"));

        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 예매나 남의 예매는 404, 회차 시작 시각을 모르면 503이고 상태는 그대로다")
    void errors() {
        String orderId = confirmed(USER, 1L);
        String reservationId = reservationId(orderId);

        assertThatThrownBy(() -> reservationService.cancel(USER, "rsv_none", key()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("RESERVATION_NOT_FOUND"));
        assertThatThrownBy(() -> reservationService.cancel(USER + 1, reservationId, key()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("RESERVATION_NOT_FOUND"));

        jdbc.update("UPDATE session_stock SET start_at = NULL WHERE session_id = ?", SESSION);
        assertThatThrownBy(() -> reservationService.cancel(USER, reservationId, key()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("SESSION_SCHEDULE_UNKNOWN"));
        assertThat(reservationStatus(orderId)).isEqualTo("CONFIRMED");
    }

    /** 좌석을 점유·결제·승인까지 진행해 예매를 확정하고 주문 ID를 돌려준다. */
    private String confirmed(long userId, Long... seatIds) {
        String orderId = orderInPayment(userId, seatIds);
        approved(orderId);
        return orderId;
    }

    private String reservationId(String orderId) {
        return jdbc.queryForObject("SELECT reservation_id FROM reservation WHERE order_id = ?", String.class, orderId);
    }

    private String reservationStatus(String orderId) {
        return jdbc.queryForObject("SELECT status FROM reservation WHERE order_id = ?", String.class, orderId);
    }

    private int cancelCommands(String orderId) {
        return (int)
                outboxTypes(orderId).stream().filter("CancelPayment"::equals).count();
    }
}
