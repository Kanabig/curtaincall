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
        jdbc.update("DELETE FROM venue_fetch_failure WHERE mt10id = ?", facility.mt10id());
        jdbc.update(
                "INSERT INTO venue (mt10id, name, hall_count, seat_scale, address, latitude, longitude, fetched_at,"
                        + " phone, website, parking_lot, barrier_elevator, barrier_parking, barrier_restroom,"
                        + " barrier_ramp, cafe, restaurant, convenience_store, playroom, nursing_room, extras_fetched)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name),"
                        + " hall_count = VALUES(hall_count), seat_scale = VALUES(seat_scale), address = VALUES(address),"
                        + " latitude = VALUES(latitude), longitude = VALUES(longitude), fetched_at = VALUES(fetched_at),"
                        + " phone = VALUES(phone), website = VALUES(website), parking_lot = VALUES(parking_lot),"
                        + " barrier_elevator = VALUES(barrier_elevator), barrier_parking = VALUES(barrier_parking),"
                        + " barrier_restroom = VALUES(barrier_restroom), barrier_ramp = VALUES(barrier_ramp),"
                        + " cafe = VALUES(cafe), restaurant = VALUES(restaurant),"
                        + " convenience_store = VALUES(convenience_store), playroom = VALUES(playroom),"
                        + " nursing_room = VALUES(nursing_room), extras_fetched = TRUE",
                facility.mt10id(),
                facility.name(),
                facility.hallCount(),
                facility.seatScale(),
                facility.address(),
                scale(facility.latitude()),
                scale(facility.longitude()),
                Timestamp.from(now),
                facility.phone(),
                facility.website(),
                facility.parkingLot(),
                facility.accessibility().elevator(),
                facility.accessibility().parking(),
                facility.accessibility().restroom(),
                facility.accessibility().ramp(),
                facility.amenities().cafe(),
                facility.amenities().restaurant(),
                facility.amenities().store(),
                facility.amenities().playroom(),
                facility.amenities().nursingRoom());
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
     * 시설 상세를 새로 받아야 하는 시설 ID. 공연이 참조하는데 아직 없거나 refreshBefore보다 오래된 것, 그리고 추가 항목 없이 받아 둔 판매 등록 공연의 시설. 끝난 공연만 참조하는
     * 시설은 갱신하지 않는다.
     */
    public List<String> findNeedingFetch(
            Instant refreshBefore, java.time.LocalDate endedAfter, int maxAttempts, int limit) {
        return jdbc.queryForList(
                "SELECT DISTINCT p.mt10id FROM performance p LEFT JOIN venue v ON v.mt10id = p.mt10id"
                        + " LEFT JOIN venue_fetch_failure f ON f.mt10id = p.mt10id"
                        + " WHERE p.mt10id IS NOT NULL AND p.end_date >= ? AND (v.mt10id IS NULL OR v.fetched_at < ?"
                        + " OR (v.extras_fetched = FALSE AND EXISTS (SELECT 1 FROM sale_performance sp"
                        + " WHERE sp.mt20id = p.mt20id)))"
                        + " AND (f.mt10id IS NULL OR f.attempts < ? OR f.last_failed_at < ?)"
                        + " ORDER BY p.mt10id LIMIT ?",
                String.class,
                java.sql.Date.valueOf(endedAfter),
                Timestamp.from(refreshBefore),
                maxAttempts,
                Timestamp.from(refreshBefore),
                limit);
    }

    /**
     * 시설 상세 파싱 실패를 센다. 한도를 넘긴 시설은 refreshBefore가 지나기 전에는 다시 받지 않는다. 5xx·타임아웃은 KOPIS 쪽 일시 장애일 수 있어 세지 않고 매 실행 다시 시도한다(호출은
     * 하루 호출 장부로 제한된다).
     */
    public void recordFailure(String mt10id, Instant now, Instant refreshBefore) {
        jdbc.update(
                "INSERT INTO venue_fetch_failure (mt10id, attempts, last_failed_at) VALUES (?, 1, ?)"
                        + " ON DUPLICATE KEY UPDATE attempts = IF(last_failed_at < ?, 1, attempts + 1),"
                        + " last_failed_at = VALUES(last_failed_at)",
                mt10id,
                Timestamp.from(now),
                Timestamp.from(refreshBefore));
    }

    public boolean isExhausted(String mt10id, int maxAttempts, Instant refreshBefore) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM venue_fetch_failure WHERE mt10id = ? AND attempts >= ? AND last_failed_at >= ?",
                Integer.class,
                mt10id,
                maxAttempts,
                Timestamp.from(refreshBefore));
        return found != null && found > 0;
    }

    /** 다시 받지 않아도 되는지. 판매 등록 공연의 시설인데 추가 항목이 없으면 기간 안이어도 false. */
    public boolean isFresh(String mt10id, Instant refreshBefore) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM venue v WHERE v.mt10id = ? AND v.fetched_at >= ? AND (v.extras_fetched = TRUE"
                        + " OR NOT EXISTS (SELECT 1 FROM performance p JOIN sale_performance sp ON sp.mt20id = p.mt20id"
                        + " WHERE p.mt10id = v.mt10id))",
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
