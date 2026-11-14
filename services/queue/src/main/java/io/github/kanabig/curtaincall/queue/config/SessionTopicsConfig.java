package io.github.kanabig.curtaincall.queue.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** event.session은 event가 소유한다. 읽는 쪽은 자기 DLT만 선언한다(브로커 자동 생성은 꺼져 있다). */
@Configuration(proxyBeanMethods = false)
public class SessionTopicsConfig {

    public static final String SESSION_TOPIC = "event.session";

    /** reservation이 소유하는 토픽. 읽는 쪽이 자기 DLT만 선언한다. */
    @Bean
    NewTopic orderDltTopic() {
        return TopicBuilder.name("reservation.order.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic sessionDltTopic() {
        return TopicBuilder.name(SESSION_TOPIC + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
