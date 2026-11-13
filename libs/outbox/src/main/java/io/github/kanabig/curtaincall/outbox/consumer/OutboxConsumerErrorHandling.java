package io.github.kanabig.curtaincall.outbox.consumer;

import io.github.kanabig.curtaincall.outbox.config.OutboxProperties;
import io.github.kanabig.curtaincall.outbox.message.EventDeserializationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** 일시 오류는 백오프(기본 1s, 2s, 4s) 3회 재시도 뒤 {@code {토픽}.DLT}로 보낸다. 봉투를 읽을 수 없는 메시지는 재시도 없이 바로 보낸다. */
public final class OutboxConsumerErrorHandling {

    public static final String DLT_SUFFIX = ".DLT";

    private OutboxConsumerErrorHandling() {}

    public static DefaultErrorHandler create(
            KafkaOperations<String, String> dltTemplate,
            OutboxProperties.Consumer properties,
            MeterRegistry meterRegistry) {
        Counter dltCount = Counter.builder("outbox.consumer.dlt")
                .description("재시도를 소진하거나 읽을 수 없어 DLT로 보낸 메시지 수")
                .register(meterRegistry);
        // 파티션 -1은 프로듀서 파티셔너가 키로 고른다. DLT 파티션 수가 원본과 같을 필요가 없다
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                dltTemplate, (record, e) -> new TopicPartition(record.topic() + DLT_SUFFIX, -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        backOff.setInitialInterval(properties.backoffInitial().toMillis());
        backOff.setMultiplier(properties.backoffMultiplier());

        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, e) -> {
                    recoverer.accept(record, e);
                    dltCount.increment();
                },
                backOff);
        // 수동 커밋 모드에서는 DLT로 넘긴 레코드의 오프셋도 직접 커밋해야 재시작 뒤 다시 읽지 않는다
        handler.setCommitRecovered(true);
        handler.addNotRetryableExceptions(EventDeserializationException.class);
        return handler;
    }
}
