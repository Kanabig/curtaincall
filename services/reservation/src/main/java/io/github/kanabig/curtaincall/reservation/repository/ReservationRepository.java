package io.github.kanabig.curtaincall.reservation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.reservation.entity.Reservation;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import io.github.kanabig.curtaincall.reservation.entity.ReservationView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {

    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() {};
    private static final String COLUMNS = "reservation_id, order_id, user_id, session_id, seat_ids, amount, status";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private static final String VIEW_SELECT = "SELECT r.reservation_id, r.order_id, r.session_id, r.seat_ids, r.amount,"
            + " r.status, r.created_at, s.start_at FROM reservation r"
            + " LEFT JOIN session_stock s ON s.session_id = r.session_id";

    private final RowMapper<Reservation> mapper = this::map;
    private final RowMapper<ReservationView> viewMapper = this::mapView;

    public ReservationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 잠그지 않고 읽는다. 주문 행을 먼저 잠그려면 order_id를 알아야 해서 쓴다. */
    public Optional<Reservation> find(String reservationId) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM reservation WHERE reservation_id = ?", mapper, reservationId)
                .stream()
                .findFirst();
    }

    /** 예매 행을 잠그고 읽는다. 주문 행을 먼저 잠근 트랜잭션 안에서만 부른다(잠금 순서: 주문 → 예매 → 좌석). */
    public Optional<Reservation> lockByOrder(String orderId) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM reservation WHERE order_id = ? FOR UPDATE", mapper, orderId)
                .stream()
                .findFirst();
    }

    /** 사용자의 예매를 예매 일시 내림차순(같으면 reservation_id 내림차순)으로 읽는다. {@code status}가 null이면 전체. */
    public List<ReservationView> findPage(long userId, ReservationStatus status, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        args.add(userId);
        String where = " WHERE r.user_id = ?";
        if (status != null) {
            where += " AND r.status = ?";
            args.add(status.name());
        }
        args.add(limit);
        args.add(offset);
        return jdbc.query(
                VIEW_SELECT + where + " ORDER BY r.created_at DESC, r.reservation_id DESC LIMIT ? OFFSET ?",
                viewMapper,
                args.toArray());
    }

    public long count(long userId, ReservationStatus status) {
        Long total = status == null
                ? jdbc.queryForObject("SELECT COUNT(*) FROM reservation WHERE user_id = ?", Long.class, userId)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM reservation WHERE user_id = ? AND status = ?",
                        Long.class,
                        userId,
                        status.name());
        return total == null ? 0 : total;
    }

    /** 본인 예매만 읽는다. 남의 예매는 없는 예매와 같게 빈 결과다. */
    public Optional<ReservationView> findView(long userId, String reservationId) {
        return jdbc
                .query(VIEW_SELECT + " WHERE r.reservation_id = ? AND r.user_id = ?", viewMapper, reservationId, userId)
                .stream()
                .findFirst();
    }

    /** 현재 상태가 {@code from}일 때만 바꾼다. 바뀌지 않았으면 false. */
    public boolean transition(String reservationId, ReservationStatus from, ReservationStatus to) {
        from.requireTransitionTo(to);
        return jdbc.update(
                        "UPDATE reservation SET status = ? WHERE reservation_id = ? AND status = ?",
                        to.name(),
                        reservationId,
                        from.name())
                == 1;
    }

    private Reservation map(ResultSet rs, int row) throws SQLException {
        return new Reservation(
                rs.getString("reservation_id"),
                rs.getString("order_id"),
                rs.getLong("user_id"),
                rs.getLong("session_id"),
                parse(rs.getString("seat_ids")),
                rs.getLong("amount"),
                ReservationStatus.valueOf(rs.getString("status")));
    }

    private ReservationView mapView(ResultSet rs, int row) throws SQLException {
        Timestamp startAt = rs.getTimestamp("start_at");
        return new ReservationView(
                rs.getString("reservation_id"),
                rs.getString("order_id"),
                rs.getLong("session_id"),
                startAt == null ? null : startAt.toInstant(),
                parse(rs.getString("seat_ids")),
                rs.getLong("amount"),
                ReservationStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant());
    }

    private List<Long> parse(String json) {
        try {
            return objectMapper.readValue(json, LONGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("seat_ids를 읽을 수 없다: " + json, e);
        }
    }
}
