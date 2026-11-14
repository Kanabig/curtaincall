package io.github.kanabig.curtaincall.reservation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.reservation.entity.Reservation;
import io.github.kanabig.curtaincall.reservation.entity.ReservationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
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
    private final RowMapper<Reservation> mapper = this::map;

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

    private List<Long> parse(String json) {
        try {
            return objectMapper.readValue(json, LONGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("seat_ids를 읽을 수 없다: " + json, e);
        }
    }
}
