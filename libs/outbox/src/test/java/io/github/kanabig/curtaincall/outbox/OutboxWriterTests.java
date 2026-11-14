package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** NFR-4 트랜잭션 원자성. */
class OutboxWriterTests extends OutboxIntegrationTest {

    @Test
    @DisplayName("도메인 변경과 outbox 기록은 같은 트랜잭션이라 함께 커밋된다")
    void commitTogether() {
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO test_order (id, status) VALUES ('o-1', 'CREATED')");
            writer.append("test.orders", "o-1", "OrderCreated", Map.of("orderId", "o-1"));
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_order", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("트랜잭션이 롤백되면 outbox 행도 사라진다")
    void rollbackTogether() {
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
                    jdbc.update("INSERT INTO test_order (id, status) VALUES ('o-2', 'CREATED')");
                    writer.append("test.orders", "o-2", "OrderCreated", Map.of("orderId", "o-2"));
                    throw new IllegalStateException("롤백 유도");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM test_order", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Integer.class))
                .isZero();
    }

    @Test
    @DisplayName("트랜잭션 밖에서 기록하면 예외")
    void requiresTransaction() {
        assertThatThrownBy(() -> writer.append("test.orders", "o-3", "OrderCreated", Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }
}
