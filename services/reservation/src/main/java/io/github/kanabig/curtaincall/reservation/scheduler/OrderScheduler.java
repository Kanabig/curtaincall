package io.github.kanabig.curtaincall.reservation.scheduler;

import io.github.kanabig.curtaincall.reservation.service.OrderSagaService;
import io.github.kanabig.curtaincall.reservation.service.OrderService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    private final OrderSagaService sagaService;
    private final OrderService orderService;

    public OrderScheduler(OrderSagaService sagaService, OrderService orderService) {
        this.sagaService = sagaService;
        this.orderService = orderService;
    }

    @Scheduled(fixedDelayString = "${curtaincall.order.scheduler.interval:5s}")
    void run() {
        sagaService.resendDue();
        orderService.expireOverdue();
    }
}
