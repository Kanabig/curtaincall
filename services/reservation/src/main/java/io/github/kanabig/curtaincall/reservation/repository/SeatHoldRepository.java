package io.github.kanabig.curtaincall.reservation.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeatHoldRepository {

    // 점유할 수 있는 좌석: 비어 있거나, 점유가 만료됐으나 아직 정리되지 않은 좌석
    private static final String HOLDABLE = "(status = 'AVAILABLE' OR (status = 'HELD' AND expires_at < ?))";

    private final JdbcTemplate jdbc;

    public SeatHoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 한도 행이 없으면 만든다. 호출하는 쪽 트랜잭션 밖(autocommit)에서 부른다. 행을 만드는 INSERT가 점유 트랜잭션 안에 있으면 처음 요청이 겹칠 때 존재하지 않는 키에
     * 갭 락을 잡아 교착이 날 수 있어서, 행을 먼저 커밋해 두고 트랜잭션은 존재하는 행만 {@code FOR UPDATE}로 잡는다.
     */
    public void ensureQuotaRow(long userId, long sessionId) {
        jdbc.update(
                "INSERT INTO user_session_quota (user_id, session_id) VALUES (?, ?)"
                        + " ON DUPLICATE KEY UPDATE user_id = user_id",
                userId,
                sessionId);
    }

    /** 같은 사용자의 점유 요청을 직렬화한다. 트랜잭션 안에서 가장 먼저 부른다. */
    public void lockQuotaRow(long userId, long sessionId) {
        jdbc.queryForObject(
                "SELECT user_id FROM user_session_quota WHERE user_id = ? AND session_id = ? FOR UPDATE",
                Long.class,
                userId,
                sessionId);
    }

    /** 사용자가 이 회차에서 가진 좌석 수: 판매된 좌석 + 만료 전 점유. */
    public int countOwned(long sessionId, long userId, Instant now) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ? AND holder_id = ?"
                        + " AND (status = 'SOLD' OR (status = 'HELD' AND expires_at >= ?))",
                Integer.class,
                sessionId,
                userId,
                Timestamp.from(now));
        return count == null ? 0 : count;
    }

    /** 점유 가능한 좌석만 한 문장으로 바꾼다. 바뀐 행 수가 요청 수와 다르면 호출하는 쪽이 롤백한다. */
    public int holdSeats(
            long sessionId, long userId, String holdId, List<Long> seatIds, Instant now, Instant expiresAt) {
        List<Object> args = new ArrayList<>();
        args.add(holdId);
        args.add(userId);
        args.add(Timestamp.from(expiresAt));
        args.add(sessionId);
        args.addAll(seatIds);
        args.add(Timestamp.from(now));
        return jdbc.update(
                "UPDATE seat_inventory SET status = 'HELD', hold_id = ?, holder_id = ?, expires_at = ?,"
                        + " version = version + 1 WHERE session_id = ? AND seat_id IN (" + placeholders(seatIds.size())
                        + ") AND " + HOLDABLE,
                args.toArray());
    }

    /**
     * 요청한 좌석 행을 {@code seat_id} 오름차순으로 잠그고 현재 상태를 돌려준다(비관적 락 전략). 모든 점유가 같은 순서로 잠가야 서로를 기다리기만 하고 교착하지 않는다. 없는
     * 좌석은 결과에 빠진다.
     *
     * <p>{@code FORCE INDEX (PRIMARY)}가 필요하다. 없으면 통계에 따라 옵티마이저가 {@code idx_seat_inventory_holder}로 회차 전체를 훑고 filesort해, {@code
     * holder_id} 순서로 필요 없는 행까지 잠가 교착이 난다. 동시성 테스트에서 간헐적으로 났고 EXPLAIN으로 확인했지만, 테스트 데이터가 작으면 PK가 골라져 나지 않을 수도
     * 있어 단위 테스트가 이 힌트를 지켜 주지는 않는다.
     */
    public List<LockedSeat> lockSeats(long sessionId, List<Long> seatIds) {
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(seatIds);
        return jdbc.query(
                "SELECT seat_id, status, expires_at FROM seat_inventory FORCE INDEX (PRIMARY) WHERE session_id = ? AND seat_id IN ("
                        + placeholders(seatIds.size()) + ") ORDER BY seat_id FOR UPDATE",
                (rs, row) -> new LockedSeat(
                        rs.getLong("seat_id"),
                        rs.getString("status"),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant()),
                args.toArray());
    }

    /** 잠근 좌석 행의 상태. */
    public record LockedSeat(long seatId, String status, Instant expiresAt) {

        public boolean holdable(Instant now) {
            return "AVAILABLE".equals(status)
                    || ("HELD".equals(status) && expiresAt != null && expiresAt.isBefore(now));
        }
    }

    /** 요청한 좌석 중 이 회차에 없는 좌석 ID. */
    public List<Long> findMissing(long sessionId, List<Long> seatIds) {
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(seatIds);
        List<Long> found = jdbc.queryForList(
                "SELECT seat_id FROM seat_inventory WHERE session_id = ? AND seat_id IN ("
                        + placeholders(seatIds.size()) + ")",
                Long.class,
                args.toArray());
        List<Long> missing = new ArrayList<>(seatIds);
        missing.removeAll(found);
        return missing;
    }

    /** 요청한 좌석 중 지금 점유할 수 없는 좌석 ID. 실패 응답에 싣는 용도라 롤백 뒤에 부른다. */
    public List<Long> findUnavailable(long sessionId, List<Long> seatIds, Instant now) {
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(seatIds);
        args.add(Timestamp.from(now));
        return jdbc.queryForList(
                "SELECT seat_id FROM seat_inventory WHERE session_id = ? AND seat_id IN ("
                        + placeholders(seatIds.size()) + ") AND NOT " + HOLDABLE + " ORDER BY seat_id",
                Long.class,
                args.toArray());
    }

    /** 본인의 점유를 푼다. 확정됐거나, 만료되어 다른 사람이 가져갔으면 0. */
    public int release(String holdId, long userId) {
        return jdbc.update(
                "UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, holder_id = NULL, expires_at = NULL,"
                        + " version = version + 1 WHERE hold_id = ? AND holder_id = ? AND status = 'HELD'",
                holdId,
                userId);
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
