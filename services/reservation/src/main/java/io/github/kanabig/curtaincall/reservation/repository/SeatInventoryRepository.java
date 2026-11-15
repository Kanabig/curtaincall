package io.github.kanabig.curtaincall.reservation.repository;

import io.github.kanabig.curtaincall.reservation.entity.NewSeat;
import io.github.kanabig.curtaincall.reservation.entity.PendingStock;
import io.github.kanabig.curtaincall.reservation.entity.SeatLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
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

    /** 재고를 만들어야 하는 회차로 등록한다. 이미 등록됐거나 준비된 회차는 그대로 두고, 시작 시각이 비어 있으면 채운다. */
    public void insertPending(long sessionId, Instant now, Instant startAt) {
        jdbc.update(
                "INSERT INTO session_stock (session_id, status, next_attempt_at, start_at) VALUES (?, 'PENDING', ?, ?)"
                        + " ON DUPLICATE KEY UPDATE start_at = COALESCE(start_at, VALUES(start_at))",
                sessionId,
                Timestamp.from(now),
                startAt == null ? null : Timestamp.from(startAt));
    }

    /** 회차 시작 시각을 바꾼다. 아직 등록되지 않은 회차면 아무것도 하지 않는다. */
    public void updateStartAt(long sessionId, Instant startAt) {
        jdbc.update("UPDATE session_stock SET start_at = ? WHERE session_id = ?", Timestamp.from(startAt), sessionId);
    }

    /** 좌석별 등급·가격을 seat_id 순으로 읽는다. */
    public List<SeatLine> findSeatLines(long sessionId, List<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(seatIds);
        String marks = String.join(",", java.util.Collections.nCopies(seatIds.size(), "?"));
        return jdbc.query(
                "SELECT seat_id, grade, price FROM seat_inventory WHERE session_id = ? AND seat_id IN (" + marks
                        + ") ORDER BY seat_id",
                (rs, row) -> new SeatLine(rs.getLong("seat_id"), rs.getString("grade"), rs.getInt("price")),
                args.toArray());
    }

    /**
     * 가격 반영이 필요하다고 표시한다. 요청 시각은 줄어들지 않으므로 이벤트가 뒤집혀 와도, 같은 이벤트가 다시 와도 결과가 같다. 아직 등록되지 않은 회차면 아무것도 하지 않는다(재고를 만들
     * 때 최신 배치를 읽는다).
     */
    public void requestPriceSync(long sessionId, Instant occurredAt) {
        jdbc.update(
                "UPDATE session_stock SET price_requested_at = GREATEST(COALESCE(price_requested_at, ?), ?)"
                        + " WHERE session_id = ?",
                Timestamp.from(occurredAt),
                Timestamp.from(occurredAt),
                sessionId);
    }

    /** 가격을 아직 반영하지 못한 READY 회차. */
    public List<PriceSyncRequest> findDuePriceSync(int limit) {
        return jdbc.query(
                "SELECT session_id, price_requested_at FROM session_stock WHERE status = 'READY'"
                        + " AND price_requested_at IS NOT NULL"
                        + " AND (price_synced_at IS NULL OR price_synced_at < price_requested_at)"
                        + " ORDER BY price_requested_at LIMIT ?",
                (rs, i) -> new PriceSyncRequest(
                        rs.getLong("session_id"),
                        rs.getTimestamp("price_requested_at").toInstant()),
                limit);
    }

    /** 재고의 현재 상태·등급·가격. */
    public List<StoredSeat> findStoredSeats(long sessionId) {
        return jdbc.query(
                "SELECT seat_id, status, grade, price FROM seat_inventory WHERE session_id = ?",
                (rs, i) -> new StoredSeat(
                        rs.getLong("seat_id"), rs.getString("status"), rs.getString("grade"), rs.getInt("price")),
                sessionId);
    }

    /** AVAILABLE인 행만 등급·가격을 바꾼다. 그 사이 점유된 행은 건드리지 않는다. */
    public int updatePricesIfAvailable(long sessionId, List<NewSeat> seats, int batchSize) {
        int updated = 0;
        for (int[] counts : jdbc.batchUpdate(
                "UPDATE seat_inventory SET grade = ?, price = ? WHERE session_id = ? AND seat_id = ?"
                        + " AND status = 'AVAILABLE'",
                seats,
                batchSize,
                (ps, seat) -> {
                    ps.setString(1, seat.grade());
                    ps.setInt(2, seat.price());
                    ps.setLong(3, sessionId);
                    ps.setLong(4, seat.seatId());
                })) {
            for (int count : counts) {
                updated += Math.max(count, 0);
            }
        }
        return updated;
    }

    /** {@code requestedAt}까지 반영했다고 표시한다. 더 늦은 값이 이미 있으면 줄이지 않는다. */
    public void markPriceSynced(long sessionId, Instant requestedAt) {
        jdbc.update(
                "UPDATE session_stock SET price_synced_at = GREATEST(COALESCE(price_synced_at, ?), ?)"
                        + " WHERE session_id = ?",
                Timestamp.from(requestedAt),
                Timestamp.from(requestedAt),
                sessionId);
    }

    public record PriceSyncRequest(long sessionId, Instant requestedAt) {}

    public record StoredSeat(long seatId, String status, String grade, int price) {}

    public Optional<Instant> findStartAt(long sessionId) {
        return jdbc
                .query(
                        "SELECT start_at FROM session_stock WHERE session_id = ? AND start_at IS NOT NULL",
                        (rs, row) -> rs.getTimestamp(1).toInstant(),
                        sessionId)
                .stream()
                .findFirst();
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

    /**
     * 가능하지 않은 좌석(만료 전 HELD, SOLD)을 seat_id 순서로 넘긴다. 만료된 HELD는 가능이므로 건너뛴다. 가능한 좌석은 읽지 않는다.
     */
    public void forEachUnavailable(long sessionId, Instant now, BiConsumer<Long, String> consumer) {
        jdbc.query(
                "SELECT seat_id, status FROM seat_inventory WHERE session_id = ?"
                        + " AND (status = 'SOLD' OR (status = 'HELD' AND expires_at >= ?)) ORDER BY seat_id",
                rs -> {
                    consumer.accept(rs.getLong(1), rs.getString(2));
                },
                sessionId,
                Timestamp.from(now));
    }

    public int countPending() {
        Integer count =
                jdbc.queryForObject("SELECT COUNT(*) FROM session_stock WHERE status = 'PENDING'", Integer.class);
        return count == null ? 0 : count;
    }

    /** 가장 오래된 PENDING 회차가 등록된 지 얼마나 됐는지. 시각은 DB 시계로 잰다. PENDING이 없으면 0. */
    public double oldestPendingAgeSeconds() {
        Double age = jdbc.queryForObject(
                "SELECT COALESCE(TIMESTAMPDIFF(MICROSECOND, MIN(created_at), CURRENT_TIMESTAMP(6)) / 1000000, 0)"
                        + " FROM session_stock WHERE status = 'PENDING'",
                Double.class);
        return age == null ? 0 : age;
    }
}
