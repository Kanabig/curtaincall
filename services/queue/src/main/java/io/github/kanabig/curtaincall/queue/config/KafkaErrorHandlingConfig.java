package io.github.kanabig.curtaincall.queue.config;

import io.github.kanabig.curtaincall.queue.messaging.InvalidSessionEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/** 일시 오류는 1s, 2s, 4s 백오프로 3회 재시도한 뒤 DLT로 보낸다. 읽을 수 없는 이벤트는 재시도 없이 바로 보낸다. */
@Configuration(proxyBeanMethods = false)
public class KafkaErrorHandlingConfig {

    /** 단일 소비자 토픽은 {토픽}.DLT, 여러 서비스가 읽는 reservation.order는 소비자별 {토픽}.queue.DLT. */
    static String deadLetterTopic(String topic) {
        return "reservation.order".equals(topic) ? SessionTopicsConfig.ORDER_DLT_TOPIC : topic + ".DLT";
    }

    @Bean
    DefaultErrorHandler sessionErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, e) -> new TopicPartition(deadLetterTopic(record.topic()), -1));
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000);
        backOff.setMultiplier(2.0);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setCommitRecovered(true);
        handler.addNotRetryableExceptions(InvalidSessionEventException.class);
        return handler;
    }
}
