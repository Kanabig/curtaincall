package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.dto.KopisFacility;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class VenueRepository {

    private final JdbcTemplate jdbc;

    public VenueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 시설과 공연장을 upsert하고, KOPIS 응답에 더는 없는 공연장은 지운다. */
    public void upsert(KopisFacility facility, Instant now) {
        jdbc.update(
                "INSERT INTO venue (mt10id, name, hall_count, seat_scale, address, latitude, longitude, fetched_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " hall_count = VALUES(hall_count), seat_scale = VALUES(seat_scale), address = VALUES(address),"
                        + " latitude = VALUES(latitude), longitude = VALUES(longitude), fetched_at = VALUES(fetched_at)",
                facility.mt10id(),
                facility.name(),
                facility.hallCount(),
                facility.seatScale(),
                facility.address(),
                scale(facility.latitude()),
                scale(facility.longitude()),
                Timestamp.from(now));
        for (KopisFacility.Hall hall : facility.halls()) {
            jdbc.update(
                    "INSERT INTO hall (mt13id, mt10id, name, seat_scale, disabled_seat_scale) VALUES (?, ?, ?, ?, ?)"
                            + " ON DUPLICATE KEY UPDATE mt10id = VALUES(mt10id), name = VALUES(name),"
                            + " seat_scale = VALUES(seat_scale), disabled_seat_scale = VALUES(disabled_seat_scale)",
                    hall.mt13id(),
                    facility.mt10id(),
                    hall.name(),
                    hall.seatScale(),
                    hall.disabledSeatScale());
        }
    }

    /**
     * 시설 상세를 새로 받아야 하는 시설 ID. 공연이 참조하는데 아직 없거나 refreshBefore보다 오래된 것. 끝난 공연만 참조하는 시설은 갱신하지 않는다.
     */
    public List<String> findNeedingFetch(Instant refreshBefore, java.time.LocalDate today, int limit) {
        return jdbc.queryForList(
                "SELECT DISTINCT p.mt10id FROM performance p LEFT JOIN venue v ON v.mt10id = p.mt10id"
                        + " WHERE p.mt10id IS NOT NULL AND p.end_date >= ? AND (v.mt10id IS NULL OR v.fetched_at < ?)"
                        + " ORDER BY p.mt10id LIMIT ?",
                String.class,
                java.sql.Date.valueOf(today),
                Timestamp.from(refreshBefore),
                limit);
    }

    public boolean isFresh(String mt10id, Instant refreshBefore) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM venue WHERE mt10id = ? AND fetched_at >= ?",
                Integer.class,
                mt10id,
                Timestamp.from(refreshBefore));
        return found != null && found > 0;
    }

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM venue", Integer.class);
    }

    private static java.math.BigDecimal scale(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(7, java.math.RoundingMode.HALF_UP);
    }
}
