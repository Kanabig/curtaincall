package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.dto.KopisPerformanceDetail;
import io.github.kanabig.curtaincall.event.dto.KopisPerformanceSummary;
import io.github.kanabig.curtaincall.event.entity.PendingPerformance;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PerformanceRepository {

    private final JdbcTemplate jdbc;

    public PerformanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 목록 응답을 upsert한다. 처음 보는 공연은 상세 조회 대기(PENDING)로 들어간다. 이미 있는 공연은 목록에 나온 값만 갱신하고, markModified면 상세를 다시 받도록
     * PENDING으로 돌린다(afterdate로 받은 목록은 모두 그 날짜 이후 수정된 공연이다). 같은 응답을 두 번 넣어도 결과가 같다.
     */
    public void upsertSummaries(List<KopisPerformanceSummary> items, boolean markModified, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.batchUpdate(
                "INSERT INTO performance (mt20id, name, facility_name, genre, area, state, start_date, end_date, open_run,"
                        + " poster, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE name = VALUES(name), facility_name = VALUES(facility_name),"
                        + " genre = VALUES(genre), area = VALUES(area), state = VALUES(state),"
                        + " start_date = VALUES(start_date), end_date = VALUES(end_date), open_run = VALUES(open_run),"
                        + " poster = VALUES(poster), updated_at = VALUES(updated_at),"
                        + " detail_attempts = IF(" + markModified + ", 0, detail_attempts),"
                        + " detail_status = IF(" + markModified + ", 'PENDING', detail_status)",
                items,
                items.size(),
                (ps, item) -> {
                    ps.setString(1, item.mt20id());
                    ps.setString(2, item.name());
                    ps.setString(3, item.facilityName());
                    ps.setString(4, item.genre());
                    ps.setString(5, item.area());
                    ps.setString(6, item.state());
                    ps.setDate(7, Date.valueOf(item.startDate()));
                    ps.setDate(8, Date.valueOf(item.endDate()));
                    ps.setBoolean(9, item.openRun());
                    ps.setString(10, item.poster());
                    ps.setTimestamp(11, ts);
                    ps.setTimestamp(12, ts);
                });
    }

    /** 상세 응답을 반영하고 DONE으로 만든다. relates는 JSON 문자열이다. */
    public void applyDetail(KopisPerformanceDetail detail, String relatesJson, Instant now) {
        Timestamp ts = Timestamp.from(now);
        jdbc.update(
                "UPDATE performance SET name = ?, mt10id = ?, mt13id = ?, facility_name = ?, start_date = ?, end_date = ?,"
                        + " genre = ?, area = ?, state = ?, open_run = ?, poster = ?, runtime = ?, age = ?,"
                        + " price_guidance = ?, date_guidance = ?, relates = ?, kopis_updated_at = ?,"
                        + " detail_status = 'DONE', detail_attempts = 0, detail_fetched_at = ?, updated_at = ?"
                        + " WHERE mt20id = ?",
                detail.name(),
                detail.mt10id(),
                detail.mt13id(),
                detail.facilityName(),
                Date.valueOf(detail.startDate()),
                Date.valueOf(detail.endDate()),
                detail.genre(),
                detail.area(),
                detail.state(),
                detail.openRun(),
                detail.poster(),
                detail.runtime(),
                detail.age(),
                detail.priceGuidance(),
                detail.dateGuidance(),
                relatesJson,
                detail.updatedAt() == null ? null : Timestamp.valueOf(detail.updatedAt()),
                ts,
                ts,
                detail.mt20id());
    }

    /** 파싱에 실패한 공연의 시도 횟수를 올리고, maxAttempts에 닿으면 FAILED로 둔다. FAILED는 목록에서 다시 수정된 것으로 오기 전까지 대기열에 들어가지 않는다. */
    public void recordDetailFailure(String mt20id, int maxAttempts) {
        jdbc.update(
                "UPDATE performance SET detail_attempts = detail_attempts + 1,"
                        + " detail_status = IF(detail_attempts + 1 >= ?, 'FAILED', detail_status) WHERE mt20id = ?",
                maxAttempts,
                mt20id);
    }

    /**
     * 다음에 상세를 받을 공연. 이미 시작했거나 오늘 시작하는 공연은 같은 순위로 두고, 그다음은 시작일이 가까운 순서다. endedAfter보다 일찍 끝난 공연은 건너뛴다. skip은 이번 실행에서 이미 시도해 실패한 공연이다.
     */
    public Optional<PendingPerformance> nextPending(
            LocalDate today, LocalDate endedAfter, int maxAttempts, List<String> skip) {
        String notIn = skip.isEmpty() ? "" : " AND mt20id NOT IN (" + "?,".repeat(skip.size() - 1) + "?)";
        List<Object> args = new ArrayList<>();
        args.add(maxAttempts);
        args.addAll(skip);
        args.add(Date.valueOf(endedAfter));
        args.add(Date.valueOf(today));
        return jdbc
                .query(
                        "SELECT mt20id, detail_attempts FROM performance WHERE detail_status = 'PENDING'"
                                + " AND detail_attempts < ?" + notIn
                                + " AND end_date >= ? ORDER BY GREATEST(start_date, ?), mt20id LIMIT 1",
                        (rs, n) -> new PendingPerformance(rs.getString(1), rs.getInt(2)),
                        args.toArray())
                .stream()
                .findFirst();
    }

    public int countPending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance WHERE detail_status = 'PENDING'", Integer.class);
    }

    public int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM performance", Integer.class);
    }
}
