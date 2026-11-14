package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.entity.SaleSession;
import io.github.kanabig.curtaincall.event.entity.SaleSession.Price;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    private static final String SELECT =
            "SELECT s.id, s.performance_id, p.mt13id, s.start_at, s.sale_open_at, s.admission_per_second,"
                    + " s.max_active_users, s.suspended FROM session s"
                    + " JOIN sale_performance sp ON sp.id = s.performance_id"
                    + " JOIN performance p ON p.mt20id = sp.mt20id";

    private final JdbcTemplate jdbc;

    public SessionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(
            long performanceId,
            Instant startAt,
            Instant saleOpenAt,
            List<Price> prices,
            int admissionPerSecond,
            int maxActiveUsers,
            Instant now) {
        KeyHolder keys = new GeneratedKeyHolder();
        Timestamp ts = Timestamp.from(now);
        jdbc.update(
                con -> {
                    var ps = con.prepareStatement(
                            "INSERT INTO session (performance_id, start_at, sale_open_at, admission_per_second,"
                                    + " max_active_users, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            java.sql.Statement.RETURN_GENERATED_KEYS);
                    ps.setLong(1, performanceId);
                    ps.setTimestamp(2, Timestamp.from(startAt));
                    ps.setTimestamp(3, Timestamp.from(saleOpenAt));
                    ps.setInt(4, admissionPerSecond);
                    ps.setInt(5, maxActiveUsers);
                    ps.setTimestamp(6, ts);
                    ps.setTimestamp(7, ts);
                    return ps;
                },
                keys);
        long id = keys.getKey().longValue();
        insertPrices(id, prices);
        return id;
    }

    /** 행을 잠그고 읽는다. 수정·삭제·중지가 오픈 시각 판단과 같은 트랜잭션에서 일어나야 하므로 FOR UPDATE다. */
    public Optional<SaleSession> findForUpdate(long id) {
        return find(id, " FOR UPDATE");
    }

    public Optional<SaleSession> find(long id) {
        return find(id, "");
    }

    private Optional<SaleSession> find(long id, String suffix) {
        return jdbc
                .query(
                        SELECT + " WHERE s.id = ?" + suffix,
                        (rs, i) -> new SaleSession(
                                rs.getLong("id"),
                                rs.getLong("performance_id"),
                                rs.getString("mt13id"),
                                rs.getTimestamp("start_at").toInstant(),
                                rs.getTimestamp("sale_open_at").toInstant(),
                                List.of(),
                                rs.getInt("admission_per_second"),
                                rs.getInt("max_active_users"),
                                rs.getBoolean("suspended")),
                        id)
                .stream()
                .findFirst()
                .map(s -> new SaleSession(
                        s.id(),
                        s.performanceId(),
                        s.venueId(),
                        s.startAt(),
                        s.saleOpenAt(),
                        prices(id),
                        s.admissionPerSecond(),
                        s.maxActiveUsers(),
                        s.suspended()));
    }

    private List<Price> prices(long sessionId) {
        return jdbc.query(
                "SELECT grade, price FROM session_price WHERE session_id = ? ORDER BY price DESC, grade",
                (rs, i) -> new Price(rs.getString(1), rs.getInt(2)),
                sessionId);
    }

    /** 일정과 운영 값을 바꾸고, prices가 null이 아니면 가격표를 통째로 바꾼다. */
    public void update(SaleSession session, List<Price> prices, Instant now) {
        jdbc.update(
                "UPDATE session SET start_at = ?, sale_open_at = ?, admission_per_second = ?, max_active_users = ?,"
                        + " updated_at = ? WHERE id = ?",
                Timestamp.from(session.startAt()),
                Timestamp.from(session.saleOpenAt()),
                session.admissionPerSecond(),
                session.maxActiveUsers(),
                Timestamp.from(now),
                session.id());
        if (prices != null) {
            jdbc.update("DELETE FROM session_price WHERE session_id = ?", session.id());
            insertPrices(session.id(), prices);
        }
    }

    public void suspend(long id, Instant now) {
        jdbc.update("UPDATE session SET suspended = TRUE, updated_at = ? WHERE id = ?", Timestamp.from(now), id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM session WHERE id = ?", id);
    }

    private void insertPrices(long sessionId, List<Price> prices) {
        jdbc.batchUpdate(
                "INSERT INTO session_price (session_id, grade, price) VALUES (?, ?, ?)",
                prices,
                prices.size(),
                (ps, p) -> {
                    ps.setLong(1, sessionId);
                    ps.setString(2, p.grade());
                    ps.setInt(3, p.price());
                });
    }
}
