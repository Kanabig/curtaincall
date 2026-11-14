package io.github.kanabig.curtaincall.reservation.repository;

import io.github.kanabig.curtaincall.reservation.entity.NewSeat;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeatInventoryRepository {

    private static final String INSERT_PREFIX =
            "INSERT IGNORE INTO seat_inventory (session_id, seat_id, grade, price) VALUES ";
    private static final String ROW = "(?, ?, ?, ?)";

    private final JdbcTemplate jdbc;

    public SeatInventoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 다중 행 INSERT로 {@code batchSize}개씩 넣는다. 행마다 왕복하지 않고, 드라이버 옵션(rewriteBatchedStatements)에도 기대지 않는다. PK가 같은 행은 건너뛰므로
     * 다시 실행해도 재고가 늘지 않는다.
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
            inserted += jdbc.update(sql.toString(), args.toArray());
        }
        return inserted;
    }

    public int count(long sessionId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seat_inventory WHERE session_id = ?", Integer.class, sessionId);
        return count == null ? 0 : count;
    }

    public void markReady(long sessionId, int seatCount) {
        jdbc.update("INSERT IGNORE INTO session_stock (session_id, seat_count) VALUES (?, ?)", sessionId, seatCount);
    }

    public boolean isReady(long sessionId) {
        Integer ready = jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_stock WHERE session_id = ?", Integer.class, sessionId);
        return ready != null && ready > 0;
    }
}
