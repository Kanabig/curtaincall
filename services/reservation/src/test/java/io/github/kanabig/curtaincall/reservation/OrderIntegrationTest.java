package io.github.kanabig.curtaincall.reservation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.reservation.entity.SeatHold;
import io.github.kanabig.curtaincall.reservation.service.OrderSagaService;
import io.github.kanabig.curtaincall.reservation.service.OrderService;
import io.github.kanabig.curtaincall.reservation.service.SeatHoldService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/** 주문·Saga 테스트의 공통 준비. 회차 1에 좌석 20개를 만들고, 결제 응답은 Kafka 없이 서비스에 직접 전달한다. */
public abstract class OrderIntegrationTest extends InventoryIntegrationTest {

    protected static final long SESSION = 1L;
    protected static final long USER = 100L;

    @Autowired
    protected SeatHoldService holdService;

    @Autowired
    protected OrderService orderService;

    @Autowired
    protected OrderSagaService sagaService;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void seedSeats() {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at) VALUES (?, 'READY', 20, NOW(6))",
                SESSION);
        for (int id = 1; id <= 20; id++) {
            jdbc.update(
                    "INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES (?, ?, 'R', 110000)",
                    SESSION,
                    id);
        }
    }

    protected SeatHold hold(long userId, Long... seatIds) {
        return holdService.hold(SESSION, userId, List.of(seatIds));
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    /** 점유 → 주문 → 결제 시작까지 진행하고 주문 ID를 돌려준다. */
    protected String orderInPayment(long userId, Long... seatIds) {
        String orderId = orderService
                .createOrder(userId, SESSION, key(), hold(userId, seatIds).holdId())
                .orderId();
        orderService.startPayment(userId, SESSION, orderId, key(), "tok_approve");
        return orderId;
    }

    protected void deliver(String eventType, String orderId, Map<String, Object> extra) {
        deliver(UUID.randomUUID().toString(), eventType, orderId, extra);
    }

    protected void deliver(String eventId, String eventType, String orderId, Map<String, Object> extra) {
        Map<String, Object> payload = new java.util.HashMap<>(extra);
        payload.put("orderId", orderId);
        payload.putIfAbsent("paymentId", "pay_" + orderId);
        sagaService.handlePaymentEvent(new EventEnvelope(
                eventId, eventType, "2026-11-14T12:00:00.000+09:00", orderId, objectMapper.valueToTree(payload)));
    }

    protected void approved(String orderId) {
        deliver("PaymentApproved", orderId, Map.of("amount", 110000));
    }

    protected String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE order_id = ?", String.class, orderId);
    }

    protected List<String> outboxTypes(String orderId) {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox WHERE message_key = ? ORDER BY id", String.class, orderId);
    }

    protected JsonNode outboxPayload(String orderId, String eventType) throws Exception {
        String envelope = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE message_key = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class,
                orderId,
                eventType);
        return objectMapper.readTree(envelope).get("payload");
    }

    protected int seatsWithStatus(String status) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND status = ?",
                Integer.class,
                SESSION,
                status);
    }

    protected <T> List<Object> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return task.call();
                    } catch (Exception e) {
                        return e;
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
