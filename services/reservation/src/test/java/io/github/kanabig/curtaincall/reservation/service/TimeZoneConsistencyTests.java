package io.github.kanabig.curtaincall.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.reservation.InventoryIntegrationTest;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 같은 행의 DB 기본값 열({@code DEFAULT CURRENT_TIMESTAMP})과 앱이 쓴 열이 같은 기준(UTC)인지 본다. JVM 기본 시간대를 KST로 바꿔도 달라지지 않아야 한다.
 */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            "curtaincall.inventory.scheduler.enabled=false",
            "curtaincall.order.scheduler.enabled=false",
            "curtaincall.outbox.relay.auto-start=false"
        })
class TimeZoneConsistencyTests extends InventoryIntegrationTest {

    @Autowired
    SeatInventoryRepository repository;

    private TimeZone original;

    @BeforeEach
    void useKstJvm() {
        original = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    @AfterEach
    void restore() {
        TimeZone.setDefault(original);
    }

    @Test
    @DisplayName("MySQL 서버와 세션 시간대는 UTC다")
    void serverAndSessionAreUtc() {
        Map<String, Object> zones = jdbc.queryForMap("SELECT @@global.time_zone AS g, @@session.time_zone AS s");

        assertThat(zones.get("g")).isEqualTo("+00:00");
        assertThat(zones.get("s")).isEqualTo("+00:00");
    }

    @Test
    @DisplayName("DEFAULT CURRENT_TIMESTAMP 열과 앱이 쓴 Instant 열이 같은 기준이다")
    void defaultColumnAndAppColumnShareBasis() {
        Instant now = Instant.now();
        repository.insertPending(901, now, now.plus(Duration.ofDays(30)));

        Map<String, Object> row = jdbc.queryForObject(
                "SELECT created_at, next_attempt_at, start_at FROM session_stock WHERE session_id = 901",
                (rs, n) -> Map.of(
                        "created", rs.getObject("created_at", LocalDateTime.class),
                        "next", rs.getObject("next_attempt_at", LocalDateTime.class),
                        "start", rs.getObject("start_at", LocalDateTime.class)));
        LocalDateTime created = (LocalDateTime) row.get("created");
        LocalDateTime next = (LocalDateTime) row.get("next");
        LocalDateTime start = (LocalDateTime) row.get("start");
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);

        assertThat(Duration.between(next, created).abs()).isLessThan(Duration.ofSeconds(10));
        assertThat(Duration.between(utcNow, created).abs()).isLessThan(Duration.ofSeconds(10));
        assertThat(start).isEqualTo(utcNow.plusDays(30).withNano(start.getNano()));
    }
}
