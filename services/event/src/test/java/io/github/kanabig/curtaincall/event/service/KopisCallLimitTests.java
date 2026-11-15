package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.entity.SyncRun;
import io.github.kanabig.curtaincall.event.entity.SyncStatus;
import io.github.kanabig.curtaincall.event.repository.KopisCallLogRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** EVT-1: 실행당 호출 상한 5, 하루 한도 100으로 한도 처리를 확인한다. */
@SpringBootTest(
        properties = {
            "curtaincall.kopis.mode=live",
            "curtaincall.kopis.api-key=test-key",
            "curtaincall.kopis.sync-max-calls=5",
            "curtaincall.kopis.scheduler.enabled=false"
        })
class KopisCallLimitTests extends EventIntegrationTest {

    private static final FakeKopisServer SERVER = new FakeKopisServer();

    @DynamicPropertySource
    static void kopis(DynamicPropertyRegistry registry) {
        registry.add("curtaincall.kopis.base-url", SERVER::baseUrl);
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop();
    }

    @Autowired
    KopisSyncService syncService;

    @Autowired
    KopisCallLogRepository callLog;

    @Autowired
    Clock clock;

    @BeforeEach
    void resetServer() {
        SERVER.reset();
    }

    @Test
    @DisplayName("실행당 상한에 닿으면 PARTIAL로 멈추고, 다음 실행이 남은 공연을 이어 받아 끝낸다")
    void resumesAfterRunLimit() {
        SyncRun first = syncService.sync("TEST");

        assertThat(first.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(first.calls()).isEqualTo(5);
        assertThat(pending()).isEqualTo(2);

        int totalCalls = first.calls();
        for (int i = 0; i < 6 && pending() > 0; i++) {
            SyncRun next = syncService.sync("TEST");
            assertThat(next.calls()).isLessThanOrEqualTo(5);
            totalCalls += next.calls();
        }

        assertThat(pending()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM venue", Integer.class))
                .isEqualTo(3);
        assertThat(callLog.count(LocalDate.now(clock))).isEqualTo(totalCalls);
        assertThat(syncService.sync("TEST").status()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    @DisplayName("하루 장부가 한도에 가까우면 남은 만큼만 호출한다")
    void respectsDailyLedger() {
        jdbc.update("INSERT INTO kopis_call_log (call_date, count) VALUES (?, 98)", LocalDate.now(clock));

        SyncRun run = syncService.sync("TEST");

        assertThat(run.status()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(run.calls()).isEqualTo(2);
        assertThat(callLog.count(LocalDate.now(clock))).isEqualTo(100);
        assertThat(SERVER.requests()).hasSize(2);

        SyncRun blocked = syncService.sync("TEST");
        assertThat(blocked.calls()).isZero();
        assertThat(callLog.count(LocalDate.now(clock))).isEqualTo(100);
    }

    @Test
    @DisplayName("동시에 호출을 요청해도 장부는 한도를 넘지 않는다")
    void ledgerIsAtomic() throws Exception {
        LocalDate today = LocalDate.now(clock);
        AtomicInteger granted = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            IntStream.range(0, 300)
                    .mapToObj(i -> pool.submit(() -> {
                        if (callLog.tryIncrement(today, 100)) {
                            granted.incrementAndGet();
                        }
                    }))
                    .toList()
                    .forEach(f -> {
                        try {
                            f.get();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    });
        }

        assertThat(granted.get()).isEqualTo(100);
        assertThat(callLog.count(today)).isEqualTo(100);
    }

    private int pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance WHERE detail_status = 'PENDING'", Integer.class);
    }
}
