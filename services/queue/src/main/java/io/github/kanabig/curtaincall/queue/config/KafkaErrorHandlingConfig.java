package io.github.kanabig.curtaincall.queue.config;

import io.github.kanabig.curtaincall.queue.messaging.InvalidSessionEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** 일시 오류는 1s, 2s, 4s 백오프로 3회 재시도한 뒤 {@code {토픽}.DLT}로 보낸다. 읽을 수 없는 이벤트는 재시도 없이 바로 보낸다. */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    @Bean
    DefaultErrorHandler sessionErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, e) -> new TopicPartition(record.topic() + ".DLT", -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        handler.addNotRetryableExceptions(InvalidSessionEventException.class);
        return handler;
    }
}
