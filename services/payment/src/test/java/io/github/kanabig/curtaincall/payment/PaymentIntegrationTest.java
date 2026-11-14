package io.github.kanabig.curtaincall.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.payment.service.PaymentCommandService;
import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 컨테이너와 가짜 PG는 테스트 JVM에서 공유하므로 테스트마다 테이블을 비우고 주문 ID를 새로 만든다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
@Import(TestContainersConfig.class)
abstract class PaymentIntegrationTest {

    static final FakePg PG = new FakePg();

    @DynamicPropertySource
    static void pgProperties(DynamicPropertyRegistry registry) {
        registry.add("payment.pg.base-url", PG::baseUrl);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PaymentCommandService commandService;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    CircuitBreaker pgApprovalBreaker;

    @BeforeEach
    void reset() {
        PG.reset();
        pgApprovalBreaker.reset();
        jdbc.update("DELETE FROM payment");
        jdbc.update("DELETE FROM outbox");
        jdbc.update("DELETE FROM processed_event");
    }

    static String newOrderId() {
        return "ord_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** reservation이 보내는 ApprovePayment 봉투. 재전송은 eventId가 다르다. */
    EventEnvelope approveCommand(String orderId, String cardToken) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                "ApprovePayment",
                "2026-11-14T12:00:00.000+09:00",
                orderId,
                objectMapper.valueToTree(
                        Map.of("orderId", orderId, "userId", "7", "amount", 120000, "cardToken", cardToken)));
    }

    /** 컨슈머가 하는 일에서 Kafka만 뺀 것: 한 트랜잭션으로 명령을 처리한다. */
    void deliver(EventEnvelope envelope) {
        tx.executeWithoutResult(status -> commandService.handle(envelope));
    }

    String status(String orderId) {
        List<String> rows = jdbc.queryForList("SELECT status FROM payment WHERE order_id = ?", String.class, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    List<String> publishedTypes(String orderId) {
        return jdbc.queryForList(
                "SELECT event_type FROM outbox WHERE message_key = ? ORDER BY id", String.class, orderId);
    }

    /** outbox 행의 payload 컬럼에는 봉투 전체가 들어 있다. 이벤트 payload만 꺼낸다. */
    JsonNode publishedPayload(String orderId, String eventType) throws Exception {
        String envelope = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE message_key = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class,
                orderId,
                eventType);
        return objectMapper.readTree(envelope).get("payload");
    }
}
