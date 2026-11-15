package io.github.kanabig.curtaincall.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.event.EventIntegrationTest;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** EVT-1: 상세 대기열 선택 규칙. */
@SpringBootTest(properties = {"curtaincall.kopis.scheduler.enabled=false", "curtaincall.kopis.bootstrap=false"})
class PerformanceRepositoryTests extends EventIntegrationTest {

    @Autowired
    PerformanceRepository performances;

    @Test
    @DisplayName("이미 끝난 공연은 건너뛰되 endedAfter를 멀리 두면 대기열에 들어온다")
    void endedPerformanceIsSkippedUnlessAsked() {
        LocalDate today = LocalDate.of(2026, 11, 14);
        jdbc.update("INSERT INTO performance (mt20id, name, start_date, end_date, created_at, updated_at)"
                + " VALUES ('PF1', '지난 공연', '2026-10-01', '2026-11-01', NOW(6), NOW(6)),"
                + " ('PF2', '진행 공연', '2026-11-01', '2026-12-01', NOW(6), NOW(6)),"
                + " ('PF3', '예정 공연', '2026-11-20', '2026-12-20', NOW(6), NOW(6))");

        assertThat(performances.nextPending(today, today, 3, List.of()).get().mt20id())
                .isEqualTo("PF2");
        assertThat(performances
                        .nextPending(today, today, 3, List.of("PF2"))
                        .get()
                        .mt20id())
                .isEqualTo("PF3");
        // 시작이 지났거나 오늘인 공연은 같은 순위, 그 안에서는 ID 순이다
        assertThat(performances
                        .nextPending(today, LocalDate.EPOCH, 3, List.of("PF2", "PF3"))
                        .get()
                        .mt20id())
                .isEqualTo("PF1");
    }
}
