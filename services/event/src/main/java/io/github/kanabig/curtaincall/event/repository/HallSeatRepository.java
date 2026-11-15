package io.github.kanabig.curtaincall.event.repository;

import io.github.kanabig.curtaincall.event.dto.SeatSpec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HallSeatRepository {

    private final JdbcTemplate jdbc;

    public HallSeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 공연장 객석 수와 휠체어석 수. 공연장이 없으면 비어 있다. */
    public Optional<HallScale> findScale(String mt13id) {
        return jdbc
                .query(
                        "SELECT seat_scale, disabled_seat_scale FROM hall WHERE mt13id = ?",
                        (rs, i) -> new HallScale(
                                (Integer) rs.getObject("seat_scale"), (Integer) rs.getObject("disabled_seat_scale")),
                        mt13id)
                .stream()
                .findFirst();
    }

    /** 배치 머리 행을 넣는다. 이미 있으면 false. 같은 공연장을 동시에 만들면 늦은 쪽은 앞선 트랜잭션이 끝날 때까지 기다렸다가 false를 받는다. */
    public boolean insertLayout(String mt13id, int seatCount, Instant now) {
        return jdbc.update(
                        "INSERT IGNORE INTO hall_layout (mt13id, seat_count, generated_at) VALUES (?, ?, ?)",
                        mt13id,
                        seatCount,
                        Timestamp.from(now))
                == 1;
    }

    public void insertSeats(String mt13id, List<SeatSpec> seats) {
        jdbc.batchUpdate(
                "INSERT INTO hall_seat (mt13id, floor, section, row_label, seat_no, x, y, depth, wheelchair)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                seats,
                1000,
                (ps, seat) -> {
                    ps.setString(1, mt13id);
                    ps.setInt(2, seat.floor());
                    ps.setString(3, seat.section());
                    ps.setString(4, seat.row());
                    ps.setInt(5, seat.number());
                    ps.setInt(6, seat.x());
                    ps.setInt(7, seat.y());
                    ps.setInt(8, seat.depth());
                    ps.setBoolean(9, seat.wheelchair());
                });
    }

    public boolean hasLayout(String mt13id) {
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM hall_layout WHERE mt13id = ?", Integer.class, mt13id);
        return found != null && found > 0;
    }

    /** 무대 쪽 열부터, 열 안에서는 왼쪽부터. */
    public List<SeatSpec> findSeats(String mt13id) {
        return querySeats(mt13id, "");
    }

    /** 잠금 읽기라 트랜잭션 스냅샷이 아니라 최신 커밋을 본다. 동시에 만든 쪽이 방금 커밋한 배치를 읽을 때 쓴다. */
    public List<SeatSpec> findSeatsLatest(String mt13id) {
        return querySeats(mt13id, " FOR SHARE");
    }

    private List<SeatSpec> querySeats(String mt13id, String lock) {
        return jdbc.query(
                "SELECT floor, section, row_label, seat_no, x, y, depth, wheelchair FROM hall_seat"
                        + " WHERE mt13id = ? ORDER BY y, x" + lock,
                (rs, i) -> new SeatSpec(
                        rs.getInt("floor"),
                        rs.getString("section"),
                        rs.getString("row_label"),
                        rs.getInt("seat_no"),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("depth"),
                        rs.getBoolean("wheelchair")),
                mt13id);
    }

    /** 좌석 ID를 포함한 배치. 배치도 응답용이라 정렬은 {@link #findSeats}와 같다. */
    public List<SeatRow> findSeatRows(String mt13id) {
        return jdbc.query(
                "SELECT seat_id, floor, section, row_label, seat_no, x, y, depth, wheelchair FROM hall_seat"
                        + " WHERE mt13id = ? ORDER BY y, x",
                (rs, i) -> new SeatRow(
                        rs.getLong("seat_id"),
                        new SeatSpec(
                                rs.getInt("floor"),
                                rs.getString("section"),
                                rs.getString("row_label"),
                                rs.getInt("seat_no"),
                                rs.getInt("x"),
                                rs.getInt("y"),
                                rs.getInt("depth"),
                                rs.getBoolean("wheelchair"))),
                mt13id);
    }

    public record SeatRow(long seatId, SeatSpec spec) {}

    public record HallScale(Integer seatScale, Integer disabledSeatScale) {}
}
