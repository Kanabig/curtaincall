package io.github.kanabig.curtaincall.reservation.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 회차 매진 판정에 쓰는 조회와 갱신. */
@Repository
public class SessionAvailabilityRepository {

    private final JdbcTemplate jdbc;

    public SessionAvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 마지막으로 발행한 판정과 version. */
    public record Published(long sessionId, boolean soldOut, long version) {}

    /** 판매 중인 회차: 재고가 준비됐고 시작 시각 전이다(시작 시각을 모르면 포함). */
    public List<Published> findSelling(Instant now) {
        return jdbc.query(
                "SELECT session_id, sold_out, availability_version FROM session_stock"
                        + " WHERE status = 'READY' AND (start_at IS NULL OR start_at > ?)",
                (rs, i) -> new Published(rs.getLong(1), rs.getBoolean(2), rs.getLong(3)),
                Timestamp.from(now));
    }

    /**
     * 모든 좌석이 SOLD인가. 점유 중이거나 점유가 만료됐어도 아직 AVAILABLE로 돌아오지 않은 좌석이 하나라도 있으면 아니다. 좌석 행이 없으면 아니다.
     */
    public boolean isSoldOut(long sessionId) {
        Boolean soldOut = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM seat_inventory WHERE session_id = ?)"
                        + " AND NOT EXISTS (SELECT 1 FROM seat_inventory WHERE session_id = ? AND status <> 'SOLD')",
                Boolean.class,
                sessionId,
                sessionId);
        return Boolean.TRUE.equals(soldOut);
    }

    /** 행을 잠그고 마지막 판정을 읽는다. 트랜잭션 안에서만 부른다. */
    public Optional<Published> lock(long sessionId) {
        return jdbc
                .query(
                        "SELECT session_id, sold_out, availability_version FROM session_stock"
                                + " WHERE session_id = ? AND status = 'READY' FOR UPDATE",
                        (rs, i) -> new Published(rs.getLong(1), rs.getBoolean(2), rs.getLong(3)),
                        sessionId)
                .stream()
                .findFirst();
    }

    public void update(long sessionId, boolean soldOut, long version) {
        jdbc.update(
                "UPDATE session_stock SET sold_out = ?, availability_version = ? WHERE session_id = ?",
                soldOut,
                version,
                sessionId);
    }
}
