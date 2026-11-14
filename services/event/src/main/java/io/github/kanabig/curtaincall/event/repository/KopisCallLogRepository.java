package io.github.kanabig.curtaincall.event.repository;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class KopisCallLogRepository {

    private final JdbcTemplate jdbc;

    public KopisCallLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그날 호출 수를 하나 올린다. 한도에 닿았으면 올리지 않고 false. 조건부 UPDATE 하나라 인스턴스나 수동 동기화가 겹쳐도 한도를 넘지 않는다. 호출 전에 올리므로 실패한
     * 호출도 센다(KOPIS는 실패도 호출로 본다고 가정).
     */
    public boolean tryIncrement(LocalDate date, int dailyLimit) {
        jdbc.update(
                "INSERT INTO kopis_call_log (call_date, count) VALUES (?, 0) ON DUPLICATE KEY UPDATE count = count",
                date);
        return jdbc.update(
                        "UPDATE kopis_call_log SET count = count + 1 WHERE call_date = ? AND count < ?",
                        date,
                        dailyLimit)
                == 1;
    }

    public int count(LocalDate date) {
        return jdbc.query(
                "SELECT count FROM kopis_call_log WHERE call_date = ?", rs -> rs.next() ? rs.getInt(1) : 0, date);
    }
}
