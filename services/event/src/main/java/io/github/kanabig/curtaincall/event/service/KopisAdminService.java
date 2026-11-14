package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.event.client.KopisClient;
import io.github.kanabig.curtaincall.event.config.KopisProperties;
import io.github.kanabig.curtaincall.event.error.EventErrorCode;
import io.github.kanabig.curtaincall.event.repository.KopisCallLogRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * ADM-4. 수동 동기화. 정기 동기화와 같은 호출 장부와 DB 락을 쓰므로 한도는 두 경로가 함께 지킨다. 남은 호출 수가 한 번의 동기화 상한보다 적으면 시작하지 않는다.
 * 동기화는 별도 스레드에서 돌고, 실행 이력이 만들어지는 대로 실행 ID를 돌려준다.
 */
@Service
public class KopisAdminService {

    private static final long START_WAIT_SECONDS = 10;

    private final KopisSyncService sync;
    private final KopisClient client;
    private final KopisCallLogRepository callLog;
    private final KopisProperties properties;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "kopis-manual-sync");
        t.setDaemon(true);
        return t;
    });

    public KopisAdminService(
            KopisSyncService sync,
            KopisClient client,
            KopisCallLogRepository callLog,
            KopisProperties properties,
            Clock clock) {
        this.sync = sync;
        this.client = client;
        this.callLog = callLog;
        this.properties = properties;
        this.clock = clock;
    }

    /** @return 실행 ID */
    public long start() {
        if (client.metered()) {
            int remaining = properties.dailyLimit() - callLog.count(LocalDate.now(clock));
            if (remaining < properties.syncMaxCalls()) {
                throw new BusinessException(EventErrorCode.KOPIS_QUOTA_LOW).with("remaining", remaining);
            }
        }
        CompletableFuture<Long> started = new CompletableFuture<>();
        executor.submit(() -> {
            try {
                sync.sync("MANUAL", started::complete);
            } catch (RuntimeException e) {
                started.completeExceptionally(e);
            } finally {
                // 건너뛰었거나 시작 전에 끝났으면 0
                started.complete(0L);
            }
        });
        long runId;
        try {
            runId = started.get(START_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동기화 시작을 기다리다 중단되었다", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("동기화를 시작하지 못했다", e);
        }
        if (runId == 0) {
            throw new BusinessException(EventErrorCode.KOPIS_SYNC_RUNNING);
        }
        return runId;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }
}
