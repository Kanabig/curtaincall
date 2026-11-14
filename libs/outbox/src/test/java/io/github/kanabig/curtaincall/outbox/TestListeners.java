package io.github.kanabig.curtaincall.outbox;

import io.github.kanabig.curtaincall.outbox.config.OutboxAutoConfiguration;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.Acknowledgment;

/** 소비 시나리오별 토픽과 리스너. 토픽 이름과 그룹 ID는 시나리오마다 고정이고 컨텍스트는 하나만 쓴다. */
@TestConfiguration(proxyBeanMethods = false)
class TestListeners {

    static final String IDEM_TOPIC = "test.idem";
    static final String FAIL_TOPIC = "test.fail";
    static final String BAD_TOPIC = "test.bad";
    static final String TRACE_TOPIC = "test.trace";
    static final String TRACE_GROUP = "test-trace-group";
    static final String IDEM_GROUP = "test-idem-group";
    static final String FAIL_GROUP = "test-fail-group";
    static final String BAD_GROUP = "test-bad-group";

    final AtomicInteger idemAttempts = new AtomicInteger();
    final AtomicInteger failAttempts = new AtomicInteger();
    final AtomicInteger badAttempts = new AtomicInteger();
    final BlockingQueue<String> traceIds = new LinkedBlockingQueue<>();

    @Bean
    NewTopic idemTopic() {
        return TopicBuilder.name(IDEM_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic failTopic() {
        return TopicBuilder.name(FAIL_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic failDlt() {
        return TopicBuilder.name(FAIL_TOPIC + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic traceTopic() {
        return TopicBuilder.name(TRACE_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic badTopic() {
        return TopicBuilder.name(BAD_TOPIC).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic badDlt() {
        return TopicBuilder.name(BAD_TOPIC + ".DLT").partitions(1).replicas(1).build();
    }

    @Bean
    TestListeners.Listeners listeners(IdempotentConsumer consumer, JdbcTemplate jdbc) {
        return new Listeners(consumer, jdbc, this);
    }

    static class Listeners {

        private final IdempotentConsumer consumer;
        private final JdbcTemplate jdbc;
        private final TestListeners counters;

        Listeners(IdempotentConsumer consumer, JdbcTemplate jdbc, TestListeners counters) {
            this.consumer = consumer;
            this.jdbc = jdbc;
            this.counters = counters;
        }

        AtomicInteger idemAttempts() {
            return counters.idemAttempts;
        }

        AtomicInteger failAttempts() {
            return counters.failAttempts;
        }

        BlockingQueue<String> traceIds() {
            return counters.traceIds;
        }

        AtomicInteger badAttempts() {
            return counters.badAttempts;
        }

        @KafkaListener(
                topics = IDEM_TOPIC,
                groupId = IDEM_GROUP,
                containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
        void onIdem(ConsumerRecord<String, String> record, Acknowledgment ack) {
            counters.idemAttempts.incrementAndGet();
            consumer.consume(
                    record,
                    ack,
                    envelope -> jdbc.update(
                            "INSERT INTO test_effect (event_id, note) VALUES (?, ?)",
                            envelope.eventId(),
                            envelope.eventType()));
        }

        @KafkaListener(
                topics = FAIL_TOPIC,
                groupId = FAIL_GROUP,
                containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
        void onFail(ConsumerRecord<String, String> record, Acknowledgment ack) {
            counters.failAttempts.incrementAndGet();
            consumer.consume(record, ack, envelope -> {
                jdbc.update(
                        "INSERT INTO test_effect (event_id, note) VALUES (?, 'before failure')", envelope.eventId());
                throw new IllegalStateException("처리 실패");
            });
        }

        @KafkaListener(
                topics = TRACE_TOPIC,
                groupId = TRACE_GROUP,
                containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
        void onTrace(ConsumerRecord<String, String> record, Acknowledgment ack) {
            // 처리 중인 스레드의 MDC에 들어 있는 추적 ID를 그대로 기록한다
            consumer.consume(record, ack, envelope -> counters.traceIds.add(String.valueOf(MDC.get("requestId"))));
        }

        @KafkaListener(
                topics = BAD_TOPIC,
                groupId = BAD_GROUP,
                containerFactory = OutboxAutoConfiguration.LISTENER_CONTAINER_FACTORY)
        void onBad(ConsumerRecord<String, String> record, Acknowledgment ack) {
            counters.badAttempts.incrementAndGet();
            consumer.consume(record, ack, envelope -> {});
        }
    }
}
