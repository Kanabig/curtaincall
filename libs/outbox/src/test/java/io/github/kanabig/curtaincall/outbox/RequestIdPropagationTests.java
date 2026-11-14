package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusOutputFormat;
import org.springframework.boot.actuate.metrics.export.prometheus.PrometheusScrapeEndpoint;
import org.springframework.kafka.core.KafkaTemplate;

/** 추적 ID: 프로듀서 MDC → outbox.request_id → 메시지 헤더 → 컨슈머 MDC. */
class RequestIdPropagationTests extends OutboxIntegrationTest {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    EventEnvelopes envelopes;

    @Autowired
    TestListeners.Listeners listeners;

    @Autowired
    PrometheusScrapeEndpoint prometheusEndpoint;

    @Test
    @DisplayName("outbox에 쓸 때의 추적 ID가 릴레이 헤더를 거쳐 컨슈머 처리 중 MDC에 복원된다")
    void requestIdSurvivesOutboxAndKafka() throws Exception {
        listeners.traceIds().clear();
        try (RequestIdContext.Scope ignored = RequestIdContext.open("req-trace-1")) {
            tx.executeWithoutResult(status -> writer.append(TestListeners.TRACE_TOPIC, "ord-1", "OrderCreated", "{}"));
        }

        relay.pollOnce();

        assertThat(listeners.traceIds().poll(15, TimeUnit.SECONDS)).isEqualTo("req-trace-1");
        assertThat(RequestIdContext.current()).isNull();
    }

    @Test
    @DisplayName("헤더가 없는 메시지는 새 추적 ID로 처리하고 처리 뒤 MDC를 비운다")
    void generatesIdWhenHeaderMissing() throws Exception {
        listeners.traceIds().clear();
        String envelope = envelopes.write(UUID.randomUUID().toString(), "OrderCreated", "ord-2", "{}");

        kafkaTemplate.send(TestListeners.TRACE_TOPIC, "ord-2", envelope).get();

        String requestId = listeners.traceIds().poll(15, TimeUnit.SECONDS);
        assertThat(RequestIdContext.isValid(requestId)).isTrue();
        assertThat(requestId).isNotEqualTo("req-trace-1");
    }

    @Test
    @DisplayName("outbox 지표가 /actuator/prometheus 출력에 나온다")
    void outboxMetricsAreScraped() {
        byte[] body = prometheusEndpoint
                .scrape(PrometheusOutputFormat.CONTENT_TYPE_004, null)
                .getBody();
        String scrape = new String(body, StandardCharsets.UTF_8);

        assertThat(scrape).contains("outbox_unpublished").contains("outbox_consumer_dlt_total");
    }
}
