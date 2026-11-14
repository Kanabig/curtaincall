package io.github.kanabig.curtaincall.event;

import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/** 컨테이너를 테스트 JVM에서 공유하므로 테스트마다 테이블을 비운다. */
@Import(TestContainersConfig.class)
public abstract class EventIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM hall");
        jdbc.update("DELETE FROM venue");
        jdbc.update("DELETE FROM performance");
        jdbc.update("DELETE FROM kopis_call_log");
        jdbc.update("DELETE FROM kopis_sync_run");
        jdbc.update("DELETE FROM kopis_sync_lock");
    }
}
