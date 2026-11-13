package io.github.kanabig.curtaincall.outbox;

import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRepository;
import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 컨테이너는 테스트 JVM에서 공유하므로 테스트마다 테이블을 비우고 토픽 이름을 새로 만든다.
 */
@SpringBootTest
@Import({TestContainersConfig.class, TestListeners.class})
abstract class OutboxIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    OutboxWriter writer;

    @Autowired
    OutboxRelay relay;

    @Autowired
    OutboxRepository repository;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    KafkaConnectionDetails kafkaConnectionDetails;

    @BeforeEach
    void cleanTables() {
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
        jdbc.update("DELETE FROM test_effect");
        jdbc.update("DELETE FROM test_order");
    }

    String bootstrapServers() {
        List<String> servers = kafkaConnectionDetails.getBootstrapServers();
        return String.join(",", servers);
    }

    long unpublished(String topic) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE topic = ? AND published_at IS NULL", Long.class, topic);
        return count == null ? 0 : count;
    }
}
