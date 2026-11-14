package io.github.kanabig.curtaincall.queue.scheduler;

import io.github.kanabig.curtaincall.queue.service.AdmissionService;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * [QUE-4, QUE-6] 1초마다 입장 처리, 10초마다 이탈 정리. 인스턴스마다 돌지만 같은 초의 입장은 Redis 쪽 tick 키가 한 번으로 줄인다. Redis가 내려가 있으면 다음 주기에
 * 다시 시도한다(경고 로그는 10초에 한 번).
 */
@Component
@ConditionalOnProperty(name = "curtaincall.queue.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AdmissionScheduler {

    private static final Logger log = LoggerFactory.getLogger(AdmissionScheduler.class);
    private static final long WARN_INTERVAL_MS = 10_000;

    private final AdmissionService service;
    private final Clock clock;
    private final AtomicLong lastWarnMs = new AtomicLong();

    public AdmissionScheduler(AdmissionService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @Scheduled(fixedRate = 1000)
    void admit() {
        run(() -> service.admitAll(clock.instant()), "입장 처리");
    }

    @Scheduled(fixedRate = 10_000)
    void removeStale() {
        run(() -> service.removeStale(clock.instant()), "이탈 정리");
    }

    private void run(Runnable task, String name) {
        try {
            task.run();
        } catch (DataAccessException e) {
            long now = clock.millis();
            long last = lastWarnMs.get();
            if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
                log.warn("{} 실패, 다음 주기에 다시 시도한다: {}", name, e.toString());
            }
        }
    }
}
