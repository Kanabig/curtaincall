package io.github.kanabig.curtaincall.event.scheduler;

import io.github.kanabig.curtaincall.event.config.KopisModes;
import io.github.kanabig.curtaincall.event.repository.PerformanceRepository;
import io.github.kanabig.curtaincall.event.service.KopisSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 키 없이 기동하면 fixture로 공연과 시설을 한 번 적재한다. 데이터가 이미 있으면 건드리지 않는다. 실패해도 기동은 계속한다. */
@Component
@ConditionalOnExpression(KopisModes.FIXTURE)
@ConditionalOnProperty(name = "curtaincall.kopis.bootstrap", havingValue = "true", matchIfMissing = true)
public class FixtureBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FixtureBootstrap.class);

    private final KopisSyncService syncService;
    private final PerformanceRepository performances;

    public FixtureBootstrap(KopisSyncService syncService, PerformanceRepository performances) {
        this.syncService = syncService;
        this.performances = performances;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (performances.count() == 0) {
                syncService.sync("BOOTSTRAP");
            }
        } catch (RuntimeException e) {
            log.warn("fixture 적재에 실패했지만 기동은 계속한다", e);
        }
    }
}
