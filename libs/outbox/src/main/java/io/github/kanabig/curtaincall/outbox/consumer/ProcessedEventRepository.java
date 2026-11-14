package io.github.kanabig.curtaincall.outbox.consumer;

import java.time.Duration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

public class ProcessedEventRepository {

    private final JdbcTemplate jdbc;

    public ProcessedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 처음 보는 이벤트면 기록하고 true, 이미 처리한 이벤트면 false. */
    public boolean markProcessed(String eventId) {
        try {
            jdbc.update("INSERT INTO processed_event (event_id) VALUES (?)", eventId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public int deleteOlderThan(Duration age, int limit) {
        return jdbc.update(
                "DELETE FROM processed_event WHERE processed_at < CURRENT_TIMESTAMP(6) - INTERVAL ? SECOND LIMIT ?",
                age.toSeconds(),
                limit);
    }
}
