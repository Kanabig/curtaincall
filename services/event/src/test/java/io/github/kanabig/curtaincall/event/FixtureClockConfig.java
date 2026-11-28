package io.github.kanabig.curtaincall.event;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** KOPIS fixture의 공연이 하나도 끝나지 않은 시점으로 시계를 고정한다. 실제 시계를 쓰면 공연 종료일이 지난 뒤 상세 대상에서 빠진다. */
@TestConfiguration
public class FixtureClockConfig {

    @Bean
    @Primary
    Clock fixtureClock() {
        return Clock.fixed(Instant.parse("2026-11-14T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    }
}
