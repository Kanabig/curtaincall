package io.github.kanabig.curtaincall.notification.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** reservation.order는 reservation이 소유한다. 읽는 토픽의 DLT만 선언한다(브로커 자동 생성은 꺼져 있다). */
@Configuration(proxyBeanMethods = false)
public class OrderTopicsConfig {

    public static final String ORDER_TOPIC = "reservation.order";

    @Bean
    NewTopic orderDltTopic() {
        return TopicBuilder.name(ORDER_TOPIC + ".notification.DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
