package io.github.kanabig.curtaincall.reservation.repository;

import io.github.kanabig.curtaincall.reservation.entity.NewSeat;
import io.github.kanabig.curtaincall.reservation.entity.PendingStock;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeatInventoryRepository {

    private static final String INSERT_PREFIX =
            "INSERT INTO seat_inventory (session_id, seat_id, grade, price) VALUES ";
    private static final String ROW = "(?, ?, ?, ?)";
    // 중복 키만 무시한다. INSERT IGNORE는 값 잘림·NULL 같은 데이터 오류까지 경고로 삼킨다
    private static final String INSERT_SUFFIX = " ON DUPLICATE KEY UPDATE session_id = session_id";

    private final JdbcTemplate jdbc;

    public SeatInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 재고를 만들어야 하는 회차로 등록한다. 이미 등록됐거나 준비된 회차는 그대로 둔다. */
    public void insertPending(long sessionId, Instant now) {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, next_attempt_at) VALUES (?, 'PENDING', ?)"
                        + " ON DUPLICATE KEY UPDATE session_id = session_id",
                sessionId,
                Timestamp.from(now));
    }

    /**
     * 처리할 시각이 된 PENDING 회차 하나를 잡는다. 다른 인스턴스가 잡은 행은 건너뛰고(SKIP LOCKED), 잡은 회차는 다음 처리 시각을 임대 만료 시각으로 밀어 둔다. 트랜잭션
     * 안에서 호출해야 한다.
     */
    public Optional<PendingStock> claimNextDue(Instant now, Instant leaseUntil) {
        List<PendingStock> due = jdbc.query(
                "SELECT session_id, attempts FROM session_stock WHERE status = 'PENDING' AND next_attempt_at <= ?"
                        + " ORDER BY next_attempt_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                (rs, rowNum) -> new PendingStock(rs.getLong("session_id"), rs.getInt("attempts")),
                Timestamp.from(now));
        if (due.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update(
                "UPDATE session_stock SET next_attempt_at = ? WHERE session_id = ?",
                Timestamp.from(leaseUntil),
                due.get(0).sessionId());
        return Optional.of(due.get(0));
    }

    /**
     * 다중 행 INSERT로 {@code batchSize}개씩 넣는다. 행마다 왕복하지 않고, 드라이버 옵션(rewriteBatchedStatements)에도 기대지 않는다. PK가 같은 행은 건너뛰고
     * 그 외 데이터 오류는 예외로 롤백하므로 다시 실행해도 재고가 늘지 않는다.
     *
     * @return 새로 만든 행 수
     */
    public int insertAll(long sessionId, List<NewSeat> seats, int batchSize) {
        int inserted = 0;
        for (int from = 0; from < seats.size(); from += batchSize) {
            List<NewSeat> chunk = seats.subList(from, Math.min(from + batchSize, seats.size()));
            StringBuilder sql = new StringBuilder(INSERT_PREFIX);
            List<Object> args = new ArrayList<>(chunk.size() * 4);
            for (int i = 0; i < chunk.size(); i++) {
                sql.append(i == 0 ? ROW : ", " + ROW);
                NewSeat seat = chunk.get(i);
                args.add(sessionId);
                args.add(seat.seatId());
                args.add(seat.grade());
                args.add(seat.price());
            }
            inserted += jdbc.update(sql.append(INSERT_SUFFIX).toString(), args.toArray());
        }
        return inserted;
    }

    /** @return PENDING이던 회차를 READY로 바꿨으면 true */
    public boolean markReady(long sessionId, int seatCount, Instant now) {
        return jdbc.update(
                        "UPDATE session_stock SET status = 'READY', seat_count = ?, ready_at = ?, last_error = NULL"
                                + " WHERE session_id = ? AND status = 'PENDING'",
                        seatCount,
                        Timestamp.from(now),
                        sessionId)
                > 0;
    }

    public void markFailed(long sessionId, Instant nextAttemptAt, String error) {
        jdbc.update(
                "UPDATE session_stock SET attempts = attempts + 1, next_attempt_at = ?, last_error = ?"
                        + " WHERE session_id = ? AND status = 'PENDING'",
                Timestamp.from(nextAttemptAt),
                error.length() > 200 ? error.substring(0, 200) : error,
                sessionId);
    }

    public boolean isReady(long sessionId) {
        Integer ready = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_stock WHERE session_id = ? AND status = 'READY'",
                Integer.class,
                sessionId);
        return ready != null && ready > 0;
    }
}
