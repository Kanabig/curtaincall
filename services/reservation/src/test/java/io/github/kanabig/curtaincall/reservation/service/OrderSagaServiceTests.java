package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** RSV-5~7 Saga: 승인·실패·좌석 소실 보상, 응답 중복과 순서 뒤바뀜, 명령 재전송. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class OrderSagaServiceTests extends OrderIntegrationTest {

    static final int REPEAT = 20;

    @Autowired
    MeterRegistry meterRegistry;

    @Test
    @DisplayName("승인되면 좌석이 SOLD, 주문이 CONFIRMED가 되고 예매와 ReservationConfirmed가 만들어진다")
    void approvedConfirms() throws Exception {
        String orderId = orderInPayment(USER, 1L, 2L);

        approved(orderId);

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed");
        var payload = outboxPayload(orderId, "ReservationConfirmed");
        assertThat(payload.get("amount").asLong()).isEqualTo(220_000);
        assertThat(payload.get("seatIds")).hasSize(2);
        assertThat(orderService.get(USER, orderId).paymentStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("점유 시간이 지났어도 아무도 가져가지 않았으면 승인 뒤 확정된다")
    void approvedAfterExpiryStillConfirms() {
        String orderId = orderInPayment(USER, 1L);
        jdbc.update(
                "UPDATE seat_inventory SET expires_at = ? WHERE seat_id = 1",
                Timestamp.from(Instant.now().minusSeconds(60)));

        approved(orderId);

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(1);
    }

    @Test
    @DisplayName("결제가 실패하면 주문은 FAILED, 좌석은 풀린다")
    void failedReleasesSeats() {
        String orderId = orderInPayment(USER, 1L, 2L);

        deliver("PaymentFailed", orderId, Map.of("reason", "DECLINED"));

        assertThat(status(orderId)).isEqualTo("FAILED");
        assertThat(seatsWithStatus("AVAILABLE")).isEqualTo(20);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment");
        assertThat(orderService.get(USER, orderId).paymentStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("승인됐는데 좌석을 다른 사람이 가져갔으면 남은 좌석을 풀고 환불하며, 환불 완료로 REFUNDED가 된다")
    void seatLostRefunds() throws Exception {
        String orderId = orderInPayment(USER, 1L, 2L);
        // 점유 좌석 하나가 다른 점유로 넘어간 상황
        jdbc.update(
                "UPDATE seat_inventory SET hold_id = ?, holder_id = ? WHERE seat_id = 2",
                UUID.randomUUID().toString(),
                USER + 1);

        approved(orderId);

        assertThat(status(orderId)).isEqualTo("REFUNDING");
        assertThat(seatsWithStatus("SOLD")).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM seat_inventory WHERE seat_id = 1", String.class))
                .isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM seat_inventory WHERE seat_id = 2", String.class))
                .isEqualTo("HELD");
        assertThat(count("reservation")).isZero();
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "CancelPayment");
        var cancel = outboxPayload(orderId, "CancelPayment");
        assertThat(cancel.get("reason").asText()).isEqualTo("SEAT_LOST");
        assertThat(cancel.get("amount").asLong()).isEqualTo(220_000);
        assertThat(cancel.get("paymentId").asText()).isEqualTo("pay_" + orderId);

        deliver("PaymentCancelled", orderId, Map.of("amount", 220_000, "reason", "SEAT_LOST"));

        assertThat(status(orderId)).isEqualTo("REFUNDED");
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "CancelPayment", "OrderRefunded");
        assertThat(orderService.get(USER, orderId).paymentStatus()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("같은 이벤트 ID의 응답은 소비 단계에서, 다른 ID의 같은 응답은 상태 전이로 한 번만 처리된다")
    void duplicateApproved() {
        String orderId = orderInPayment(USER, 1L);
        String eventId = UUID.randomUUID().toString();

        deliver(eventId, "PaymentApproved", orderId, Map.of());
        deliver("PaymentApproved", orderId, Map.of());
        deliver("PaymentApproved", orderId, Map.of());

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed");
    }

    @Test
    @DisplayName("끝난 주문에 늦게 온 다른 응답과, 상태에 맞지 않는 응답은 무시한다")
    void outOfOrderResponses() {
        String confirmed = orderInPayment(USER, 1L);
        approved(confirmed);
        deliver("PaymentFailed", confirmed, Map.of("reason", "DECLINED"));
        deliver("PaymentCancelled", confirmed, Map.of());
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(1);

        String failed = orderInPayment(USER + 1, 2L);
        deliver("PaymentFailed", failed, Map.of("reason", "DECLINED"));
        approved(failed);
        assertThat(status(failed)).isEqualTo("FAILED");
        assertThat(count("reservation")).isEqualTo(1);

        // 환불 요청 전에 환불 완료가 먼저 온 경우(PAYMENT_REQUESTED)는 무시한다
        String requested = orderInPayment(USER + 2, 3L);
        deliver("PaymentCancelled", requested, Map.of());
        assertThat(status(requested)).isEqualTo("PAYMENT_REQUESTED");

        // 모르는 주문
        deliver("PaymentApproved", "ord_unknown", Map.of());
    }

    @RepeatedTest(REPEAT)
    @DisplayName("같은 승인 응답이 동시에 여러 번 와도 예매·확정 이벤트는 하나다")
    void concurrentApproved() throws Exception {
        String orderId = orderInPayment(USER, 1L, 2L);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                approved(orderId);
                return null;
            });
        }

        List<Object> results = runConcurrently(tasks);

        assertThat(results).allSatisfy(r -> assertThat(r).isNull());
        assertThat(status(orderId)).isEqualTo("CONFIRMED");
        assertThat(count("reservation")).isEqualTo(1);
        assertThat(seatsWithStatus("SOLD")).isEqualTo(2);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed");
    }

    @RepeatedTest(REPEAT)
    @DisplayName("승인과 실패 응답이 동시에 와도 하나만 반영되고 좌석 상태가 주문과 맞는다")
    void concurrentApprovedAndFailed() throws Exception {
        String orderId = orderInPayment(USER, 1L);
        List<Callable<Object>> tasks = new ArrayList<>();
        tasks.add(() -> {
            approved(orderId);
            return null;
        });
        tasks.add(() -> {
            deliver("PaymentFailed", orderId, Map.of("reason", "DECLINED"));
            return null;
        });

        runConcurrently(tasks);

        String status = status(orderId);
        assertThat(status).isIn("CONFIRMED", "FAILED");
        assertThat(seatsWithStatus("SOLD")).isEqualTo(status.equals("CONFIRMED") ? 1 : 0);
        assertThat(count("reservation")).isEqualTo(status.equals("CONFIRMED") ? 1 : 0);
    }

    @Test
    @DisplayName("응답이 없으면 2분 뒤 ApprovePayment를 다시 보내고 점유 연장을 갱신한다")
    void resendsAfterTimeout() {
        String orderId = orderInPayment(USER, 1L);
        makeDue(orderId);
        jdbc.update(
                "UPDATE seat_inventory SET expires_at = ? WHERE seat_id = 1",
                Timestamp.from(Instant.now().plusSeconds(60)));

        int resent = sagaService.resendDue();

        assertThat(resent).isEqualTo(1);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ApprovePayment");
        assertThat(jdbc.queryForObject("SELECT expires_at FROM seat_inventory WHERE seat_id = 1", Timestamp.class)
                        .toInstant())
                .isAfter(Instant.now().plusSeconds(600));
        // 막 재전송했으니 바로 다시 돌려도 보내지 않는다
        assertThat(sagaService.resendDue()).isZero();
        // 재전송한 명령에도 같은 orderId 키와 같은 payload가 실린다
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(DISTINCT message_key) FROM outbox WHERE event_type = 'ApprovePayment'",
                        Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("재전송은 3번까지이고, 그 뒤에도 응답이 없으면 지표를 올리고 멈춘 채 기다리다 늦은 승인으로 확정된다")
    void stopsAfterMaxResendsAndStillConfirms() {
        String orderId = orderInPayment(USER, 1L);
        double stalledBefore = meterRegistry.counter("order.payment.stalled").count();

        for (int i = 0; i < 3; i++) {
            makeDue(orderId);
            assertThat(sagaService.resendDue()).isEqualTo(1);
        }
        assertThat(outboxTypes(orderId)).hasSize(4);
        makeDue(orderId);
        sagaService.resendDue();

        assertThat(outboxTypes(orderId)).hasSize(4);
        assertThat(meterRegistry.counter("order.payment.stalled").count()).isEqualTo(stalledBefore + 1);
        assertThat(jdbc.queryForObject(
                        "SELECT next_resend_at FROM orders WHERE order_id = ?", Timestamp.class, orderId))
                .isNull();
        assertThat(sagaService.resendDue()).isZero();

        approved(orderId);

        assertThat(status(orderId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("응답이 온 주문은 재전송 대상에서 빠진다")
    void answeredOrdersAreNotResent() {
        String orderId = orderInPayment(USER, 1L);
        approved(orderId);
        makeDue(orderId);

        assertThat(sagaService.resendDue()).isZero();
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment", "ReservationConfirmed");
    }

    private void makeDue(String orderId) {
        jdbc.update(
                "UPDATE orders SET next_resend_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                orderId);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
