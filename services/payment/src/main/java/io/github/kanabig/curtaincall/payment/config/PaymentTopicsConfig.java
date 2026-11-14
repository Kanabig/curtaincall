package io.github.kanabig.curtaincall.payment.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** payment가 소유하는 토픽. 명령 토픽의 스키마와 응답 토픽의 발행은 모두 payment 책임이다(spec/events.md). */
@Configuration
public class PaymentTopicsConfig {

    public static final String COMMAND_TOPIC = "payment.command";
    public static final String PAYMENT_TOPIC = "payment.payment";

    @Bean
    NewTopic paymentCommandTopic() {
        return TopicBuilder.name(COMMAND_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic paymentCommandDltTopic() {
        return TopicBuilder.name(COMMAND_TOPIC + ".DLT")
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    NewTopic paymentEventTopic() {
        return TopicBuilder.name(PAYMENT_TOPIC).partitions(3).replicas(1).build();
    }
}
