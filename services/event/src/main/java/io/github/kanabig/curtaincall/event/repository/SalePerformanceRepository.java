package io.github.kanabig.curtaincall.event.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SalePerformanceRepository {

    private final JdbcTemplate jdbc;

    public SalePerformanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 이미 등록된 공연이면 아무것도 하지 않고 false. */
    public boolean insertIfAbsent(String mt20id, Instant now) {
        return jdbc.update(
                        "INSERT IGNORE INTO sale_performance (mt20id, created_at) VALUES (?, ?)",
                        mt20id,
                        Timestamp.from(now))
                == 1;
    }

    public Optional<Long> findId(String mt20id) {
        return jdbc.query("SELECT id FROM sale_performance WHERE mt20id = ?", (rs, i) -> rs.getLong(1), mt20id).stream()
                .findFirst();
    }

    /** 회차 이벤트의 venueId가 되는 공연장 ID(mt13id). */
    public Optional<String> findHallId(long id) {
        return jdbc
                .query(
                        "SELECT p.mt13id FROM sale_performance s JOIN performance p ON p.mt20id = s.mt20id"
                                + " WHERE s.id = ?",
                        (rs, i) -> rs.getString(1),
                        id)
                .stream()
                .findFirst();
    }

    /** 등록된 공연의 KOPIS 가격 안내 원문. 공연이 없으면 비어 있고, 원문이 없으면 null이다. */
    public Optional<String> findPriceGuidance(long id) {
        return jdbc
                .query(
                        "SELECT p.price_guidance FROM sale_performance s JOIN performance p ON p.mt20id = s.mt20id"
                                + " WHERE s.id = ?",
                        (rs, i) -> rs.getString(1),
                        id)
                .stream()
                .findFirst();
    }
}
