package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.FixtureClockConfig;
import io.github.kanabig.curtaincall.event.entity.SyncRun;
import io.github.kanabig.curtaincall.event.repository.KopisCallLogRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** EVT-1: 추가 항목 없이 받아 둔 판매 등록 공연·시설을 호출 한도 안에서 한 번 다시 받는다. */
@SpringBootTest(
        properties = {
            "curtaincall.kopis.mode=live",
            "curtaincall.kopis.api-key=test-key",
            "curtaincall.kopis.sync-max-calls=5",
            "curtaincall.kopis.scheduler.enabled=false"
        })
@Import(FixtureClockConfig.class)
class KopisRefetchTests extends EventIntegrationTest {

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
    void legacyRegisteredPerformances() {
        SERVER.reset();
        legacy("PF305718", "FC000680");
        legacy("PF305735", "FC001535");
    }

    /** 추가 항목이 생기기 전에 상세와 시설을 받고 판매 등록까지 한 공연. */
    private void legacy(String mt20id, String mt10id) {
        jdbc.update(
                "INSERT INTO performance (mt20id, name, mt10id, start_date, end_date, detail_status, created_at,"
                        + " updated_at) VALUES (?, '이전 공연', ?, '2026-11-11', '2027-01-31', 'DONE', NOW(6), NOW(6))",
                mt20id,
                mt10id);
        jdbc.update("INSERT INTO sale_performance (mt20id, created_at) VALUES (?, NOW(6))", mt20id);
        jdbc.update(
                "INSERT INTO venue (mt10id, name, fetched_at) VALUES (?, '이전 시설', ?)",
                mt10id,
                Timestamp.from(clock.instant()));
    }

    @Test
    @DisplayName("판매 등록 공연과 시설을 먼저 다시 받고, 실행당 상한과 하루 장부를 넘지 않으며, 한 번 받으면 다시 부르지 않는다")
    void refetchesWithinBudget() {
        int total = 0;
        for (int i = 0; i < 6; i++) {
            SyncRun run = syncService.sync("TEST");
            assertThat(run.calls()).isLessThanOrEqualTo(5);
            total += run.calls();
            // 다시 받는 동안에도 등록 공연은 DONE이다
            assertThat(count("performance WHERE detail_status = 'DONE' AND mt20id IN ('PF305718', 'PF305735')"))
                    .isEqualTo(2);
        }

        assertThat(callLog.count(LocalDate.now(clock))).isEqualTo(total);
        assertThat(detailRequests().subList(0, 2)).containsExactlyInAnyOrder("PF305718", "PF305735");
        assertThat(detailRequests()).containsOnlyOnce("PF305718").containsOnlyOnce("PF305735");
        assertThat(count("performance WHERE extras_fetched = TRUE AND is_child = TRUE AND mt20id = 'PF305718'"))
                .isOne();
        assertThat(count("performance WHERE is_creative = TRUE AND mt20id = 'PF305735'"))
                .isOne();
        assertThat(count("venue WHERE extras_fetched = TRUE AND mt10id IN ('FC000680', 'FC001535')"))
                .isEqualTo(2);
        assertThat(requests("/prfplc/FC000680")).isOne();
        assertThat(requests("/prfplc/FC001535")).isOne();
    }

    @Test
    @DisplayName("하루 장부가 차 있으면 다시 받기도 호출하지 않는다")
    void respectsDailyLedger() {
        jdbc.update("INSERT INTO kopis_call_log (call_date, count) VALUES (?, 100)", LocalDate.now(clock));

        assertThat(syncService.sync("TEST").calls()).isZero();
        assertThat(SERVER.requests()).isEmpty();
    }

    @Test
    @DisplayName("다시 받은 상세가 계속 깨져도 공연은 DONE으로 두고 3번까지만 시도한다")
    void brokenRefetchKeepsDone() {
        SERVER.brokenDetailId = "PF305718";

        for (int i = 0; i < 8; i++) {
            syncService.sync("TEST");
        }

        assertThat(detailRequests().stream().filter("PF305718"::equals)).hasSize(3);
        assertThat(count("performance WHERE mt20id = 'PF305718' AND detail_status = 'DONE'"))
                .isOne();
    }

    private List<String> detailRequests() {
        return SERVER.requests().stream()
                .map(uri -> uri.getPath())
                .filter(path -> path.startsWith("/pblprfr/"))
                .map(path -> path.substring("/pblprfr/".length()))
                .toList();
    }

    private long requests(String path) {
        return SERVER.requests().stream()
                .filter(uri -> uri.getPath().equals(path))
                .count();
    }

    private int count(String fromAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromAndWhere, Integer.class);
    }
}
