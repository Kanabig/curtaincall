package io.github.kanabig.curtaincall.reservation.scheduler;

import io.github.kanabig.curtaincall.reservation.service.SeatInventoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** [EVT-9] PENDING 회차의 재고 생성을 주기적으로 처리한다. */
@Component
@ConditionalOnProperty(
        prefix = "curtaincall.inventory.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SeatInventoryScheduler {

    private final SeatInventoryService service;

    public SeatInventoryScheduler(SeatInventoryService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${curtaincall.inventory.scheduler.interval:2s}")
    void run() {
        service.processPending();
    }
}
