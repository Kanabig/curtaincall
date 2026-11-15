package io.github.kanabig.curtaincall.event.scheduler;

import io.github.kanabig.curtaincall.event.service.KopisSyncService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 04:00(KST) 동기화. 인스턴스가 여럿이어도 DB 락으로 하나만 돈다. */
@Component
@ConditionalOnProperty(name = "curtaincall.kopis.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class KopisSyncScheduler {

    private final KopisSyncService syncService;

    public KopisSyncScheduler(KopisSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "${curtaincall.kopis.cron:0 0 4 * * *}", zone = "${curtaincall.kopis.zone:Asia/Seoul}")
    public void run() {
        syncService.sync("SCHEDULED");
    }
}
