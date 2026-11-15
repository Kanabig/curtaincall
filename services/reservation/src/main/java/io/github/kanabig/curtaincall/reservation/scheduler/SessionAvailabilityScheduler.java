package io.github.kanabig.curtaincall.reservation.scheduler;

import io.github.kanabig.curtaincall.reservation.service.SessionAvailabilityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** [QUE-8] 판매 중인 회차의 매진 여부를 주기적으로(기본 2초) 점검한다. */
@Component
@ConditionalOnProperty(
        prefix = "curtaincall.availability.scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SessionAvailabilityScheduler {

    private final SessionAvailabilityService service;

    public SessionAvailabilityScheduler(SessionAvailabilityService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${curtaincall.availability.scheduler.interval:2s}")
    void run() {
        service.checkAll();
    }
}
