package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import io.github.kanabig.curtaincall.reservation.dto.OrderResponse;
import io.github.kanabig.curtaincall.reservation.error.ReservationErrorCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** RSV-5 주문 생성, 결제 시작, 멱등키, 점유 연장. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class OrderServiceTests extends OrderIntegrationTest {

    static final int REPEAT = 20;

    private void setHoldExpiry(String holdId, Instant expiresAt) {
        jdbc.update("UPDATE seat_inventory SET expires_at = ? WHERE hold_id = ?", Timestamp.from(expiresAt), holdId);
    }

    @Test
    @DisplayName("주문은 점유 좌석 가격의 합을 금액으로 PENDING_PAYMENT로 생성된다")
    void createOrder() {
        var hold = hold(USER, 1L, 2L);

        OrderResponse order = orderService.createOrder(USER, SESSION, key(), hold.holdId());

        assertThat(order.amount()).isEqualTo(220_000);
        assertThat(order.status().name()).isEqualTo("PENDING_PAYMENT");
        assertThat(order.orderId()).startsWith("ord_");
        assertThat(status(order.orderId())).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    @DisplayName("없는 점유, 남의 점유로는 주문할 수 없다(HOLD_NOT_FOUND)")
    void holdNotFound() {
        var other = hold(USER + 1, 3L);

        assertThatThrownBy(() -> orderService.createOrder(USER, SESSION, key(), "no-such-hold"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_NOT_FOUND));
        assertThatThrownBy(() -> orderService.createOrder(USER, SESSION, key(), other.holdId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_NOT_FOUND));
    }

    @Test
    @DisplayName("만료된 점유로 주문하거나 결제를 시작하면 HOLD_EXPIRED")
    void expiredHold() {
        var expiredAtCreate = hold(USER, 1L);
        setHoldExpiry(expiredAtCreate.holdId(), Instant.now().minusSeconds(5));
        assertThatThrownBy(() -> orderService.createOrder(USER, SESSION, key(), expiredAtCreate.holdId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_EXPIRED));

        var hold = hold(USER, 2L);
        String orderId =
                orderService.createOrder(USER, SESSION, key(), hold.holdId()).orderId();
        setHoldExpiry(hold.holdId(), Instant.now().minusSeconds(5));
        assertThatThrownBy(() -> orderService.startPayment(USER, SESSION, orderId, key(), "tok_approve"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_EXPIRED));
        assertThat(status(orderId)).isEqualTo("PENDING_PAYMENT");
        assertThat(outboxTypes(orderId)).isEmpty();
    }

    @Test
    @DisplayName("점유 남은 시간이 30초 미만이면 결제를 시작하지 않는다(HOLD_EXPIRING)")
    void holdExpiring() {
        var hold = hold(USER, 1L);
        String orderId =
                orderService.createOrder(USER, SESSION, key(), hold.holdId()).orderId();
        setHoldExpiry(hold.holdId(), Instant.now().plusSeconds(10));

        assertThatThrownBy(() -> orderService.startPayment(USER, SESSION, orderId, key(), "tok_approve"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.HOLD_EXPIRING));
        assertThat(outboxTypes(orderId)).isEmpty();
    }

    @Test
    @DisplayName("결제 시작은 PAYMENT_REQUESTED로 바꾸고 ApprovePayment를 outbox에 남긴다")
    void startPayment() throws Exception {
        var hold = hold(USER, 1L, 2L);
        String orderId =
                orderService.createOrder(USER, SESSION, key(), hold.holdId()).orderId();

        var response = orderService.startPayment(USER, SESSION, orderId, key(), "tok_approve");

        assertThat(response.status().name()).isEqualTo("PAYMENT_REQUESTED");
        assertThat(status(orderId)).isEqualTo("PAYMENT_REQUESTED");
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment");
        var payload = outboxPayload(orderId, "ApprovePayment");
        assertThat(payload.get("orderId").asText()).isEqualTo(orderId);
        assertThat(payload.get("userId").asText()).isEqualTo(String.valueOf(USER));
        assertThat(payload.get("amount").asLong()).isEqualTo(220_000);
        assertThat(payload.get("cardToken").asText()).isEqualTo("tok_approve");
    }

    @Test
    @DisplayName("남의 주문은 조회·결제할 수 없고, PENDING_PAYMENT가 아닌 주문은 새 키로 결제를 시작해도 거절한다")
    void ownershipAndState() {
        String orderId = orderInPayment(USER, 1L);

        assertThatThrownBy(() -> orderService.get(USER + 1, orderId))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.ORDER_NOT_FOUND));
        assertThatThrownBy(() -> orderService.startPayment(USER + 1, SESSION, orderId, key(), "tok_approve"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.ORDER_NOT_FOUND));
        assertThatThrownBy(() -> orderService.startPayment(USER, SESSION, orderId, key(), "tok_approve"))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.ORDER_NOT_PAYABLE));
        assertThat(orderService.get(USER, orderId).paymentStatus()).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 주문을 다시 요청하면 첫 결과를 돌려주고, 다른 키로 같은 점유를 주문하면 ORDER_ALREADY_EXISTS")
    void createIdempotentByKey() {
        var hold = hold(USER, 1L);
        String key = key();

        OrderResponse first = orderService.createOrder(USER, SESSION, key, hold.holdId());
        OrderResponse again = orderService.createOrder(USER, SESSION, key, hold.holdId());

        assertThat(again).isEqualTo(first);
        assertThat(count("orders")).isEqualTo(1);
        assertThatThrownBy(() -> orderService.createOrder(USER, SESSION, key(), hold.holdId()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.ORDER_ALREADY_EXISTS));
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 결제를 시작해도 ApprovePayment는 한 번만 나간다")
    void payIdempotentByKey() {
        var hold = hold(USER, 1L);
        String orderId =
                orderService.createOrder(USER, SESSION, key(), hold.holdId()).orderId();
        String key = key();

        var first = orderService.startPayment(USER, SESSION, orderId, key, "tok_approve");
        var again = orderService.startPayment(USER, SESSION, orderId, key, "tok_approve");

        assertThat(again).isEqualTo(first);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment");
    }

    @RepeatedTest(REPEAT)
    @DisplayName("같은 키 요청이 동시에 와도 주문은 하나, 결제 명령은 하나다")
    void concurrentSameKey() throws Exception {
        var hold = hold(USER, 1L, 2L);
        String createKey = key();
        List<Callable<OrderResponse>> creates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            creates.add(() -> orderService.createOrder(USER, SESSION, createKey, hold.holdId()));
        }
        List<Object> created = runConcurrently(creates);
        assertThat(created).allSatisfy(r -> assertThat(r).isInstanceOf(OrderResponse.class));
        assertThat(created.stream().map(r -> ((OrderResponse) r).orderId()).distinct())
                .hasSize(1);
        String orderId = ((OrderResponse) created.get(0)).orderId();

        String payKey = key();
        List<Callable<Object>> pays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pays.add(() -> orderService.startPayment(USER, SESSION, orderId, payKey, "tok_approve"));
        }
        List<Object> paid = runConcurrently(pays);

        assertThat(paid).allSatisfy(r -> assertThat(r).isNotInstanceOf(Exception.class));
        assertThat(count("orders")).isEqualTo(1);
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment");
    }

    @RepeatedTest(REPEAT)
    @DisplayName("키가 다른 주문 생성이 같은 점유로 동시에 와도 하나만 성공하고 나머지는 ORDER_ALREADY_EXISTS이다")
    void concurrentCreateDifferentKeys() throws Exception {
        var hold = hold(USER, 1L, 2L);
        List<Callable<Object>> creates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            creates.add(() -> orderService.createOrder(USER, SESSION, key(), hold.holdId()));
        }

        List<Object> results = runConcurrently(creates);

        assertThat(results.stream().filter(r -> !(r instanceof Exception))).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof Exception))
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                be -> assertThat(be.errorCode()).isEqualTo(ReservationErrorCode.ORDER_ALREADY_EXISTS)));
        assertThat(count("orders")).isEqualTo(1);
    }

    @RepeatedTest(REPEAT)
    @DisplayName("키가 다른 결제 시작이 동시에 와도 하나만 성공하고 나머지는 ORDER_NOT_PAYABLE이다")
    void concurrentDifferentKeys() throws Exception {
        var hold = hold(USER, 1L);
        String orderId =
                orderService.createOrder(USER, SESSION, key(), hold.holdId()).orderId();
        List<Callable<Object>> pays = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pays.add(() -> orderService.startPayment(USER, SESSION, orderId, key(), "tok_approve"));
        }

        List<Object> results = runConcurrently(pays);

        assertThat(results.stream().filter(r -> !(r instanceof Exception))).hasSize(1);
        assertThat(results.stream().filter(r -> r instanceof Exception))
                .allSatisfy(e -> assertThat(e)
                        .isInstanceOfSatisfying(
                                BusinessException.class,
                                be -> assertThat(be.errorCode()).isEqualTo(ReservationErrorCode.ORDER_NOT_PAYABLE)));
        assertThat(outboxTypes(orderId)).containsExactly("ApprovePayment");
    }

    @Test
    @DisplayName("결제 중에는 점유가 결제 마감까지 늘어나 원래 TTL이 지난 뒤에도 같은 사용자의 추가 점유는 한도 초과, 다른 사용자는 그 좌석을 못 가져간다")
    void holdExtendedWhilePaying() {
        orderInPayment(USER, 1L, 2L, 3L);
        long ttlSeconds = 300;

        // 원래 점유 TTL(5분)을 넘겨도 만료되지 않도록 마감이 늘어나 있다
        Instant expiresAt = jdbc.queryForObject(
                        "SELECT MIN(expires_at) FROM seat_inventory WHERE holder_id = ?", Timestamp.class, USER)
                .toInstant();
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(ttlSeconds + 60));

        // 3매를 결제 중인 사용자가 다른 탭에서 2매를 더 잡으면 한도(4매)를 넘는다
        assertThatThrownBy(() -> hold(USER, 10L, 11L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.PURCHASE_LIMIT_EXCEEDED));
        // 다른 사용자는 만료 판정 시점이 늘어난 좌석을 잡지 못한다
        assertThatThrownBy(() -> hold(USER + 1, 1L))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ReservationErrorCode.SEAT_ALREADY_HELD));
    }

    @Test
    @DisplayName("결제를 시작하지 않은 주문은 점유가 만료되면 EXPIRED로 정리되고, 결제 중인 주문은 그대로다")
    void expireOverdue() {
        var held = hold(USER, 1L);
        String pending =
                orderService.createOrder(USER, SESSION, key(), held.holdId()).orderId();
        String paying = orderInPayment(USER + 1, 2L);
        jdbc.update(
                "UPDATE orders SET hold_expires_at = ? WHERE order_id IN (?, ?)",
                Timestamp.from(Instant.now().minusSeconds(1)),
                pending,
                paying);

        int expired = orderService.expireOverdue();

        assertThat(expired).isEqualTo(1);
        assertThat(status(pending)).isEqualTo("EXPIRED");
        assertThat(status(paying)).isEqualTo("PAYMENT_REQUESTED");
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
