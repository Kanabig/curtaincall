package io.github.kanabig.curtaincall.reservation.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.reservation.entity.Order;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private static final TypeReference<List<Long>> LONGS = new TypeReference<>() {};
    private static final String COLUMNS = "order_id, hold_id, user_id, session_id, seat_ids, amount, status,"
            + " hold_expires_at, card_token, payment_id, command_sent_count";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<Order> mapper = this::map;

    public OrderRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insert(Order order) {
        jdbc.update(
                "INSERT INTO orders (order_id, hold_id, user_id, session_id, seat_ids, amount, status, hold_expires_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                order.orderId(),
                order.holdId(),
                order.userId(),
                order.sessionId(),
                json(order.seatIds()),
                order.amount(),
                order.status().name(),
                Timestamp.from(order.holdExpiresAt()));
    }

    public Optional<Order> find(String orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE order_id = ?", mapper, orderId).stream()
                .findFirst();
    }

    /** 주문 행을 잠그고 읽는다. 주문을 바꾸는 모든 경로가 이 락으로 직렬화된다. */
    public Optional<Order> lock(String orderId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM orders WHERE order_id = ? FOR UPDATE", mapper, orderId).stream()
                .findFirst();
    }

    public boolean existsByHold(String holdId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE hold_id = ?", Integer.class, holdId);
        return count != null && count > 0;
    }

    /** 현재 상태가 {@code from}일 때만 바꾼다. 바뀌지 않았으면 false. */
    public boolean transition(String orderId, OrderStatus from, OrderStatus to, Instant now) {
        from.requireTransitionTo(to);
        return jdbc.update(
                        "UPDATE orders SET status = ?, updated_at = ? WHERE order_id = ? AND status = ?",
                        to.name(),
                        Timestamp.from(now),
                        orderId,
                        from.name())
                == 1;
    }

    /** 결제를 시작한다. 첫 명령을 보낸 것으로 센다. */
    public boolean startPayment(String orderId, String cardToken, Instant now, Instant nextResendAt) {
        return jdbc.update(
                        "UPDATE orders SET status = ?, card_token = ?, command_sent_count = 1, next_resend_at = ?,"
                                + " updated_at = ? WHERE order_id = ? AND status = ?",
                        OrderStatus.PAYMENT_REQUESTED.name(),
                        cardToken,
                        Timestamp.from(nextResendAt),
                        Timestamp.from(now),
                        orderId,
                        OrderStatus.PENDING_PAYMENT.name())
                == 1;
    }

    /** 명령을 다시 보냈다고 기록한다. {@code nextResendAt}이 null이면 더 보내지 않는다. */
    public void recordResend(String orderId, Instant nextResendAt, Instant now) {
        jdbc.update(
                "UPDATE orders SET command_sent_count = command_sent_count + 1, next_resend_at = ?, updated_at = ?"
                        + " WHERE order_id = ?",
                nextResendAt == null ? null : Timestamp.from(nextResendAt),
                Timestamp.from(now),
                orderId);
    }

    /** 결제가 끝나 응답을 더 기다리지 않는다. 호출하는 쪽이 주문 행을 이미 잠갔다. */
    public void completePayment(String orderId, String paymentId) {
        jdbc.update("UPDATE orders SET payment_id = ?, next_resend_at = NULL WHERE order_id = ?", paymentId, orderId);
    }

    /** 재전송 시각이 지난 PAYMENT_REQUESTED 주문을 잠그고 가져온다. 다른 인스턴스가 잡은 행은 건너뛴다. */
    public List<Order> lockResendDue(Instant now, int limit) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM orders WHERE status = ? AND next_resend_at <= ?"
                        + " ORDER BY next_resend_at LIMIT ? FOR UPDATE SKIP LOCKED",
                mapper,
                OrderStatus.PAYMENT_REQUESTED.name(),
                Timestamp.from(now),
                limit);
    }

    /** 점유가 만료된 채 결제를 시작하지 않은 주문을 만료로 바꾼다. */
    public int expirePending(Instant now) {
        return jdbc.update(
                "UPDATE orders SET status = ?, updated_at = ? WHERE status = ? AND hold_expires_at < ?",
                OrderStatus.EXPIRED.name(),
                Timestamp.from(now),
                OrderStatus.PENDING_PAYMENT.name(),
                Timestamp.from(now));
    }

    public int countByStatus(OrderStatus status) {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE status = ?", Integer.class, status.name());
        return count == null ? 0 : count;
    }

    /** 확정된 예매 행을 만든다. 주문당 하나(order_id UNIQUE)라 같은 주문을 두 번 확정하면 예외다. */
    public void insertReservation(String reservationId, Order order, Instant now) {
        jdbc.update(
                "INSERT INTO reservation (reservation_id, order_id, user_id, session_id, seat_ids, amount, status,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?)",
                reservationId,
                order.orderId(),
                order.userId(),
                order.sessionId(),
                json(order.seatIds()),
                order.amount(),
                Timestamp.from(now));
    }

    private Order map(ResultSet rs, int row) throws SQLException {
        return new Order(
                rs.getString("order_id"),
                rs.getString("hold_id"),
                rs.getLong("user_id"),
                rs.getLong("session_id"),
                parse(rs.getString("seat_ids")),
                rs.getLong("amount"),
                OrderStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("hold_expires_at").toInstant(),
                rs.getString("card_token"),
                rs.getString("payment_id"),
                rs.getInt("command_sent_count"));
    }

    private String json(List<Long> seatIds) {
        try {
            return objectMapper.writeValueAsString(seatIds);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<Long> parse(String json) {
        try {
            return objectMapper.readValue(json, LONGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("seat_ids를 읽을 수 없다: " + json, e);
        }
    }
}
