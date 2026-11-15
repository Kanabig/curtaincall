package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.client.FixtureKopisClient;
import io.github.kanabig.curtaincall.event.scheduler.FixtureBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** EVT-2: KOPIS 키 없이(mode 기본값 auto) 기동한 상태. 외부 호출 없이 fixture로 적재한다. */
@SpringBootTest(properties = "curtaincall.kopis.scheduler.enabled=false")
class FixtureBootstrapTests extends EventIntegrationTest {

    @Autowired
    FixtureBootstrap bootstrap;

    @Autowired
    FixtureKopisClient fixtureClient;

    @Autowired
    KopisSyncService syncService;

    @Test
    @DisplayName("데이터가 없으면 fixture로 공연·시설을 적재하고 호출 장부는 쓰지 않는다")
    void loadsFixtureWithoutKey() {
        bootstrap.run(null);

        assertThat(count("performance WHERE detail_status = 'DONE'")).isEqualTo(3);
        assertThat(count("venue")).isEqualTo(3);
        assertThat(count("hall")).isEqualTo(HALLS);
        assertThat(count("kopis_call_log")).isZero();
        assertThat(jdbc.queryForObject("SELECT calls FROM kopis_sync_run", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("이미 데이터가 있으면 다시 적재하지 않고, 다시 동기화해도 결과가 같다")
    void idempotent() {
        bootstrap.run(null);
        bootstrap.run(null);
        syncService.sync("TEST");

        assertThat(count("kopis_sync_run WHERE trigger_type = 'BOOTSTRAP'")).isEqualTo(1);
        assertThat(count("performance")).isEqualTo(3);
        assertThat(count("venue")).isEqualTo(3);
        assertThat(count("hall")).isEqualTo(HALLS);
    }

    private static final int HALLS = 12;

    private int count(String fromAndWhere) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + fromAndWhere, Integer.class);
    }
}
