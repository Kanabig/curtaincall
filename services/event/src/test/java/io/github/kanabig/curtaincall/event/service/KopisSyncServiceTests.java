package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.FakeKopisServer;
import io.github.kanabig.curtaincall.event.entity.SyncRun;
import io.github.kanabig.curtaincall.event.entity.SyncStatus;
import io.github.kanabig.curtaincall.event.repository.KopisSyncLockRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** EVT-1, EVT-3: 증분 동기화, KOPIS 실패 시 기존 데이터 유지, 중복 실행 방지. */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "curtaincall.kopis.mode=live",
            "curtaincall.kopis.api-key=test-key",
            "curtaincall.kopis.read-timeout=300ms",
            "curtaincall.kopis.scheduler.enabled=false"
        })
class KopisSyncServiceTests extends EventIntegrationTest {

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
    KopisSyncLockRepository lockRepository;

    @Autowired
    Clock clock;

    @Autowired
    TestRestTemplate restTemplate;

    @BeforeEach
    void resetServer() {
        SERVER.reset();
    }

    @Test
    @DisplayName("처음 동기화하면 공연·시설·공연장이 저장되고 호출 수가 장부에 남는다")
    void loadsPerformancesAndVenues() {
        SyncRun run = syncService.sync("TEST");

        // 31일 창 3개의 목록 + 상세 3 + 시설 3
        assertThat(run.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.calls()).isEqualTo(9);
        assertThat(run.listCompleted()).isTrue();
        assertThat(count("performance")).isEqualTo(3);
        assertThat(count("performance WHERE detail_status = 'DONE'")).isEqualTo(3);
        assertThat(count("venue")).isEqualTo(3);
        assertThat(count("hall")).isEqualTo(HALLS);
        assertThat(jdbc.queryForObject(
                        "SELECT count FROM kopis_call_log WHERE call_date = ?", Integer.class, LocalDate.now(clock)))
                .isEqualTo(9);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM performance WHERE mt20id = 'PF305735'");
        assertThat(row.get("price_guidance")).isEqualTo("전석 60,000원");
        assertThat(row.get("mt10id")).isEqualTo("FC001535");
        assertThat(row.get("source")).isEqualTo("KOPIS");
    }

    @Test
    @DisplayName("다시 돌리면 첫 실행이 끝난 날짜 기준으로 증분 조회하고, 바뀐 공연이 없으면 목록 호출만 한다")
    void incrementalSecondRun() {
        syncService.sync("TEST");
        SERVER.requests().clear();

        SyncRun second = syncService.sync("TEST");

        assertThat(second.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(second.calls()).isEqualTo(3);
        assertThat(second.afterDate()).isEqualTo(LocalDate.now(clock));
        assertThat(SERVER.requests()).extracting(URI::getQuery).allMatch(q -> q.contains("afterdate="));
        assertThat(count("performance")).isEqualTo(3);
    }

    @Test
    @DisplayName("목록에 다시 나온 공연(수정됨)은 상세를 다시 받고, 같은 응답을 거듭 저장해도 행이 늘지 않는다")
    void modifiedPerformanceIsFetchedAgain() {
        syncService.sync("TEST");
        SERVER.afterdateListBody = FakeKopisServer.fixture("pblprfr-list.xml");
        SERVER.requests().clear();

        SyncRun second = syncService.sync("TEST");

        // 창 3개가 같은 목록을 돌려주므로 목록 3 + 상세 3. 시설은 이미 신선해서 부르지 않는다
        assertThat(second.calls()).isEqualTo(6);
        assertThat(second.detailsFetched()).isEqualTo(3);
        assertThat(second.facilitiesFetched()).isZero();
        assertThat(count("performance")).isEqualTo(3);
        assertThat(count("venue")).isEqualTo(3);
    }

    @Test
    @DisplayName("KOPIS가 5xx면 실행을 FAILED로 끝내고 기존 공연·시설 데이터와 헬스체크는 그대로다")
    void keepsDataWhenKopisFails() {
        syncService.sync("TEST");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM performance ORDER BY mt20id");
        SERVER.status = 503;

        SyncRun failed = syncService.sync("TEST");

        assertThat(failed.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(failed.error()).contains("503").doesNotContain("test-key");
        assertThat(failed.calls()).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT * FROM performance ORDER BY mt20id"))
                .isEqualTo(before);
        assertThat(count("venue")).isEqualTo(3);
        assertThat(restTemplate.getForObject("/actuator/health", String.class)).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("타임아웃도 같은 방식으로 실행을 끝낸다")
    void timeoutFailsRun() {
        SERVER.delayMs = 1500;

        SyncRun failed = syncService.sync("TEST");

        assertThat(failed.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(failed.calls()).isEqualTo(1);
        assertThat(count("performance")).isZero();
    }

    @Test
    @DisplayName("깨진 응답이면 실행을 FAILED로 끝내고 다음 실행이 같은 기준일로 다시 받는다")
    void brokenListResponse() {
        SyncRun first = syncService.sync("TEST");
        assertThat(first.listCompleted()).isTrue();
        SERVER.overrideBody = "<dbs><db>";

        SyncRun failed = syncService.sync("TEST");

        assertThat(failed.status()).isEqualTo(SyncStatus.FAILED);
        assertThat(failed.listCompleted()).isFalse();
        assertThat(failed.error()).contains("파싱");
        assertThat(count("performance")).isEqualTo(3);
    }

    @Test
    @DisplayName("상세 응답이 깨진 공연은 시도 횟수를 올리고 반복되면 FAILED로 두며, 다른 공연은 계속 받는다")
    void brokenDetailIsIsolated() {
        SERVER.brokenDetailId = "PF305720";

        SyncRun run = syncService.sync("TEST");

        assertThat(run.status()).isEqualTo(SyncStatus.SUCCESS);
        assertThat(run.parseFailures()).isEqualTo(1);
        assertThat(run.detailsFetched()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT detail_attempts FROM performance WHERE mt20id = 'PF305720'", Integer.class))
                .isEqualTo(1);

        // 목록에 다시 나오지 않는 동안 실행마다 한 번씩 시도하다 세 번째 실패에서 FAILED가 된다
        syncService.sync("TEST");
        syncService.sync("TEST");
        assertThat(jdbc.queryForObject("SELECT detail_status FROM performance WHERE mt20id = 'PF305720'", String.class))
                .isEqualTo("FAILED");
        SyncRun after = syncService.sync("TEST");
        assertThat(after.calls()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 인스턴스가 락을 잡고 있으면 동기화하지 않고 건너뛴다")
    void skipsWhenLocked() {
        Instant now = clock.instant();
        assertThat(lockRepository.tryAcquire("kopis-sync", "other", now, now.plusSeconds(60)))
                .isTrue();

        SyncRun skipped = syncService.sync("TEST");

        assertThat(skipped.status()).isEqualTo(SyncStatus.SKIPPED);
        assertThat(SERVER.requests()).isEmpty();

        lockRepository.release("kopis-sync", "other");
        assertThat(syncService.sync("TEST").status()).isEqualTo(SyncStatus.SUCCESS);
    }

    @Test
    @DisplayName("락 임대가 만료되면 다른 인스턴스가 이어받는다")
    void expiredLockIsTakenOver() {
        Instant now = clock.instant();
        lockRepository.tryAcquire("kopis-sync", "crashed", now.minusSeconds(120), now.minusSeconds(60));

        assertThat(syncService.sync("TEST").status()).isEqualTo(SyncStatus.SUCCESS);
    }

    private static final int HALLS = 12;

    private int count(String fromAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromAndWhere, Integer.class);
    }
}
