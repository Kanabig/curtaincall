package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.entity.LastSync;
import io.github.kanabig.curtaincall.event.entity.SyncRun;
import io.github.kanabig.curtaincall.event.entity.SyncStatus;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SyncRunRepository {

    private static final RowMapper<SyncRun> MAPPER = (rs, n) -> new SyncRun(
            rs.getLong("id"),
            SyncStatus.valueOf(rs.getString("status")),
            rs.getObject("after_date", LocalDate.class),
            rs.getObject("synced_until", LocalDate.class),
            rs.getBoolean("list_completed"),
            rs.getInt("calls"),
            rs.getInt("listed"),
            rs.getInt("details_fetched"),
            rs.getInt("facilities_fetched"),
            rs.getInt("parse_failures"),
            rs.getString("error"));

    private final JdbcTemplate jdbc;

    public SyncRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long start(String trigger, boolean metered, Instant now, LocalDate afterDate) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(
                con -> {
                    var ps = con.prepareStatement(
                            "INSERT INTO kopis_sync_run (trigger_type, metered, status, started_at, after_date) VALUES (?, ?, 'RUNNING', ?, ?)",
                            java.sql.Statement.RETURN_GENERATED_KEYS);
                    ps.setString(1, trigger);
                    ps.setBoolean(2, metered);
                    ps.setTimestamp(3, Timestamp.from(now));
                    ps.setDate(4, afterDate == null ? null : Date.valueOf(afterDate));
                    return ps;
                },
                keys);
        return keys.getKey().longValue();
    }

    public void finish(long id, Instant now, SyncRun result) {
        jdbc.update(
                "UPDATE kopis_sync_run SET status = ?, finished_at = ?, list_completed = ?, synced_until = ?, calls = ?, listed = ?,"
                        + " details_fetched = ?, facilities_fetched = ?, parse_failures = ?, error = ? WHERE id = ?",
                result.status().name(),
                Timestamp.from(now),
                result.listCompleted(),
                result.syncedUntil() == null ? null : Date.valueOf(result.syncedUntil()),
                result.calls(),
                result.listed(),
                result.detailsFetched(),
                result.facilitiesFetched(),
                result.parseFailures(),
                result.error(),
                id);
    }

    /** 실제 KOPIS에서 목록을 끝까지 받은 마지막 실행. 없으면 처음이거나 한 번도 끝까지 못 받은 것이라 전체를 받는다. */
    public Optional<LastSync> lastListCompleted(ZoneId zone) {
        return jdbc
                .query(
                        "SELECT started_at, synced_until FROM kopis_sync_run WHERE list_completed = TRUE AND metered = TRUE"
                                + " ORDER BY started_at DESC LIMIT 1",
                        (rs, n) -> new LastSync(
                                rs.getTimestamp(1).toInstant().atZone(zone).toLocalDate(),
                                rs.getObject(2, LocalDate.class)))
                .stream()
                .findFirst();
    }

    public Optional<SyncRun> find(long id) {
        return jdbc.query("SELECT * FROM kopis_sync_run WHERE id = ?", MAPPER, id).stream()
                .findFirst();
    }
}
