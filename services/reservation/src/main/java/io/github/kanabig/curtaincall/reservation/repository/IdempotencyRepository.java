package io.github.kanabig.curtaincall.reservation.repository;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IdempotencyRepository {

    private final JdbcTemplate jdbc;

    public IdempotencyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 키 행을 만든다. 트랜잭션 밖(autocommit)에서 부른다. 안에서 만들면 같은 키의 첫 요청이 겹칠 때 갭 락으로 교착한다. */
    public void ensure(long userId, String scope, String key) {
        jdbc.update(
                "INSERT INTO idempotency_key (user_id, scope, idem_key) VALUES (?, ?, ?)"
                        + " ON DUPLICATE KEY UPDATE user_id = user_id",
                userId,
                scope,
                key);
    }

    /** 키 행을 잠근다. 같은 키의 요청은 여기서 줄을 서고, 앞선 요청이 커밋하면 저장된 응답이 보인다. 응답이 없으면 null. */
    public String lockResponse(long userId, String scope, String key) {
        List<String> rows = jdbc.query(
                "SELECT response FROM idempotency_key WHERE user_id = ? AND scope = ? AND idem_key = ? FOR UPDATE",
                (rs, n) -> rs.getString("response"),
                userId,
                scope,
                key);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void saveResponse(long userId, String scope, String key, String response) {
        jdbc.update(
                "UPDATE idempotency_key SET response = ? WHERE user_id = ? AND scope = ? AND idem_key = ?",
                response,
                userId,
                scope,
                key);
    }
}
