package io.github.kanabig.curtaincall.outbox.relay;

import io.github.kanabig.curtaincall.outbox.message.OutboxMessage;
import java.time.Duration;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;

public class OutboxRepository {

    // 같은 키의 앞선 미발행 행이 있으면(다른 릴레이가 처리 중이거나 앞서 실패한 행) 건너뛰어 키별 순서를 지킨다
    private static final String FETCH = """
            SELECT o.id, o.topic, o.message_key, o.event_id, o.event_type, o.payload, o.request_id
            FROM outbox o
            WHERE o.published_at IS NULL
              AND NOT EXISTS (
                  SELECT 1 FROM outbox p
                  WHERE p.message_key = o.message_key AND p.id < o.id AND p.published_at IS NULL)
            ORDER BY o.id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String topic, String key, String eventId, String eventType, String payload, String requestId) {
        jdbc.update(
                "INSERT INTO outbox (topic, message_key, event_id, event_type, payload, request_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                topic,
                key,
                eventId,
                eventType,
                payload,
                requestId);
    }

    /** 호출하는 트랜잭션이 끝날 때까지 가져온 행을 잠근다. */
    public List<OutboxMessage> lockUnpublished(int limit) {
        return jdbc.query(
                FETCH,
                (rs, rowNum) -> new OutboxMessage(
                        rs.getLong("id"),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("event_id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("request_id")),
                limit);
    }

    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        jdbc.update(
                "UPDATE outbox SET published_at = CURRENT_TIMESTAMP(6) WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }

    public int deletePublishedOlderThan(Duration age, int limit) {
        return jdbc.update(
                "DELETE FROM outbox WHERE published_at IS NOT NULL"
                        + " AND published_at < CURRENT_TIMESTAMP(6) - INTERVAL ? SECOND LIMIT ?",
                age.toSeconds(),
                limit);
    }

    public long countUnpublished() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}
