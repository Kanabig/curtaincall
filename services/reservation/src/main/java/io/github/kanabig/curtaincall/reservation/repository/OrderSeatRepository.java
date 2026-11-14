package io.github.kanabig.curtaincall.reservation.repository;

import io.github.kanabig.curtaincall.reservation.entity.HeldSeat;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 주문이 hold_id로 좌석 재고를 읽고 바꾸는 쿼리. 좌석은 항상 seat_id 오름차순으로 잠가 점유 UPDATE와 잠금 순서를 맞춘다. */
@Repository
public class OrderSeatRepository {

    private final JdbcTemplate jdbc;

    public OrderSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** hold_id를 가진 좌석을 상태와 상관없이 잠그고 읽는다. */
    public List<HeldSeat> lockByHold(String holdId) {
        return jdbc.query(
                "SELECT seat_id, session_id, holder_id, status, price, expires_at FROM seat_inventory"
                        + " WHERE hold_id = ? ORDER BY seat_id FOR UPDATE",
                (rs, row) -> new HeldSeat(
                        rs.getLong("seat_id"),
                        rs.getLong("session_id"),
                        (Long) rs.getObject("holder_id"),
                        rs.getString("status"),
                        rs.getInt("price"),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant()),
                holdId);
    }

    /** 확정. 만료 시각은 보지 않는다. 시간이 지났어도 아무도 가져가지 않았으면 결제한 사람의 좌석이다(점유 hold_id가 그대로다). */
    public int markSold(String holdId) {
        return jdbc.update(
                "UPDATE seat_inventory SET status = 'SOLD', version = version + 1 WHERE hold_id = ? AND status = 'HELD'",
                holdId);
    }

    /** 이 점유로 잡혀 있는 좌석을 푼다. 다른 사람에게 넘어가 hold_id가 바뀐 좌석은 건드리지 않는다. */
    public int releaseHeld(String holdId) {
        return jdbc.update(
                "UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, holder_id = NULL, expires_at = NULL,"
                        + " version = version + 1 WHERE hold_id = ? AND status = 'HELD'",
                holdId);
    }

    /** 환불이 끝난 예매의 좌석을 판매 가능으로 되돌린다. 점유 때 남은 hold_id로 찾는다. */
    public int releaseSold(String holdId) {
        return jdbc.update(
                "UPDATE seat_inventory SET status = 'AVAILABLE', hold_id = NULL, holder_id = NULL, expires_at = NULL,"
                        + " version = version + 1 WHERE hold_id = ? AND status = 'SOLD'",
                holdId);
    }

    /**
     * 결제를 시작하며 점유를 {@code expiresAt}까지 늘린다. 아직 만료되지 않은 내 점유만 늘리므로, 바뀐 행 수가 주문 좌석 수보다 적으면 호출하는 쪽이 거절한다.
     */
    public int extendForPayment(String holdId, long userId, Instant now, Instant expiresAt) {
        return jdbc.update(
                "UPDATE seat_inventory SET expires_at = ?, version = version + 1 WHERE hold_id = ? AND holder_id = ?"
                        + " AND status = 'HELD' AND expires_at > ?",
                Timestamp.from(expiresAt),
                holdId,
                userId,
                Timestamp.from(now));
    }

    /** 명령을 다시 보낼 때 연장을 갱신한다. 이미 결제 중인 주문이라 만료 조건은 두지 않는다(아무도 가져가지 않았으면 hold_id가 그대로다). */
    public int renewForPayment(String holdId, Instant expiresAt) {
        return jdbc.update(
                "UPDATE seat_inventory SET expires_at = ?, version = version + 1 WHERE hold_id = ? AND status = 'HELD'",
                Timestamp.from(expiresAt),
                holdId);
    }
}
