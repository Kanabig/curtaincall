package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.OrderIntegrationTest;
import io.github.kanabig.curtaincall.reservation.repository.OrderRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 만료와, 같은 주문 행을 잠근 채 상태를 바꾸는 트랜잭션(결제 시작)의 잠금 순서 충돌. 래치로 순서를 고정한다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class OrderExpireDeadlockTests extends OrderIntegrationTest {

    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private OrderRepository orderRepository;

    @RepeatedTest(5)
    void expireAndPaymentStartDoNotDeadlock() throws Exception {
        String orderId = orderInPaymentCandidate();
        jdbc.update(
                "UPDATE orders SET hold_expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                orderId);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // 결제 시작 경로와 같은 순서: 주문 행 FOR UPDATE → 상태 UPDATE
        CompletableFuture<Void> payment =
                CompletableFuture.runAsync(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                    orderRepository.lock(orderId);
                    locked.countDown();
                    await(release);
                    orderRepository.startPayment(orderId, "tok", Instant.now(), Instant.now());
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> expire = CompletableFuture.supplyAsync(() -> orderService.expireOverdue());

        // 만료는 잠긴 행을 건너뛰어 기다리지 않고 끝난다. 그 뒤에 결제 쪽을 풀어 준다
        int expired = expire.get(10, TimeUnit.SECONDS);
        release.countDown();
        payment.get(10, TimeUnit.SECONDS);

        assertThat(expired).isZero();
        assertThat(status(orderId)).isEqualTo("PAYMENT_REQUESTED");
    }

    @Test
    void skippedOrderIsExpiredOnNextRun() throws Exception {
        String orderId = orderInPaymentCandidate();
        jdbc.update(
                "UPDATE orders SET hold_expires_at = ? WHERE order_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)),
                orderId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder =
                CompletableFuture.runAsync(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
                    orderRepository.lock(orderId);
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(orderService.expireOverdue()).isZero();
        release.countDown();
        holder.get(10, TimeUnit.SECONDS);

        assertThat(orderService.expireOverdue()).isEqualTo(1);
        assertThat(status(orderId)).isEqualTo("EXPIRED");
    }

    private String orderInPaymentCandidate() {
        return orderService
                .createOrder(USER, SESSION, key(), hold(USER, 1L).holdId())
                .orderId();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
