package io.github.kanabig.curtaincall.reservation.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * 토픽 이름과 reservation이 소유하는 토픽. 명령·결제 토픽은 payment가 소유하므로 이름만 알고, 읽는 토픽의 DLT만 선언한다(브로커 자동 생성은 꺼져 있다).
 */
@Configuration(proxyBeanMethods = false)
public class OrderTopicsConfig {

    public static final String PAYMENT_COMMAND_TOPIC = "payment.command";
    public static final String PAYMENT_TOPIC = "payment.payment";
    public static final String ORDER_TOPIC = "reservation.order";

    @Bean
    NewTopic orderTopic() {
        return TopicBuilder.name(ORDER_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentEventDltTopic() {
        return TopicBuilder.name(PAYMENT_TOPIC + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }
}
