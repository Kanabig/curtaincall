package io.github.kanabig.curtaincall.reservation.scheduler;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.reservation.service.OrderSagaService;
import io.github.kanabig.curtaincall.reservation.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** [RSV-7] 응답이 없는 결제의 명령 재전송과, 결제를 시작하지 않은 주문의 만료 정리. */
@Component
@ConditionalOnProperty(
        prefix = "curtaincall.order.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OrderScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderScheduler.class);

    private static final int MAX_EXPIRE_BATCHES = 20;

    private final OrderSagaService sagaService;
    private final OrderService orderService;
    private final MeterRegistry meterRegistry;

    public OrderScheduler(OrderSagaService sagaService, OrderService orderService, MeterRegistry meterRegistry) {
        this.sagaService = sagaService;
        this.orderService = orderService;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${curtaincall.order.scheduler.interval:5s}")
    void run() {
        // 한 작업이 잠금 경합으로 실패해도 다른 작업은 돌고, 실패한 작업은 다음 주기에 다시 한다
        attempt("resend", sagaService::resendDue);
        attempt("expire", this::expireAll);
    }

    /** 배치 상한만큼 정리했으면 남은 것이 있을 수 있어 이어서 부른다. 한 주기가 길어지지 않게 횟수를 제한한다. */
    private int expireAll() {
        int total = 0;
        for (int i = 0; i < MAX_EXPIRE_BATCHES; i++) {
            int expired = orderService.expireOverdue();
            total += expired;
            if (expired < OrderService.EXPIRE_BATCH) {
                break;
            }
        }
        return total;
    }

    private void attempt(String task, IntSupplier work) {
        try {
            work.getAsInt();
        } catch (PessimisticLockingFailureException e) {
            String cause = cause(e);
            count(task, cause);
            log.warn("주문 스케줄 작업이 잠금 경합으로 실패했다. 다음 주기에 다시 한다: task={}, cause={}", task, cause, e);
        } catch (RuntimeException e) {
            // 다른 작업이 같은 주기에 건너뛰어지지 않게 막고, 원인은 ERROR로 남긴다
            count(task, "other");
            log.error("주문 스케줄 작업이 실패했다. 다음 주기에 다시 한다: task={}", task, e);
        }
    }

    private static String cause(PessimisticLockingFailureException e) {
        if (e instanceof DeadlockLoserDataAccessException) {
            return "deadlock";
        }
        if (e instanceof CannotAcquireLockException) {
            // MySQL 1213(교착)도 CannotAcquireLock으로 올 수 있어 SQLState·벤더 코드로 나눈다
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof SQLException sql) {
                    return sql.getErrorCode() == 1213 ? "deadlock" : "lock_timeout";
                }
            }
            return "lock_timeout";
        }
        return "other";
    }

    private void count(String task, String cause) {
        meterRegistry
                .counter(MetricNames.ORDER_SCHEDULER_FAILURE, "task", task, "cause", cause)
                .increment();
    }
}
