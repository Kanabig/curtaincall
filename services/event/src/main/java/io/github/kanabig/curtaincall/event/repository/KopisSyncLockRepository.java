package io.github.kanabig.curtaincall.event.repository;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DB 임대 락. 프로세스가 죽어도 locked_until이 지나면 다른 인스턴스가 잡는다. */
@Repository
public class KopisSyncLockRepository {

    private static final Timestamp EPOCH = Timestamp.from(Instant.EPOCH);

    private final JdbcTemplate jdbc;

    public KopisSyncLockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean tryAcquire(String name, String owner, Instant now, Instant until) {
        jdbc.update(
                "INSERT INTO kopis_sync_lock (name, locked_until) VALUES (?, ?) ON DUPLICATE KEY UPDATE name = name",
                name,
                EPOCH);
        return jdbc.update(
                        "UPDATE kopis_sync_lock SET owner = ?, locked_until = ? WHERE name = ? AND locked_until < ?",
                        owner,
                        Timestamp.from(until),
                        name,
                        Timestamp.from(now))
                == 1;
    }

    public void release(String name, String owner) {
        jdbc.update("UPDATE kopis_sync_lock SET locked_until = ? WHERE name = ? AND owner = ?", EPOCH, name, owner);
    }
}
