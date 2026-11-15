package io.github.kanabig.curtaincall.queue.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** queue가 읽는 토픽의 DLT를 선언한다. 토픽은 소유 서비스가 만들고(브로커 자동 생성은 꺼져 있다), DLT 이름은 {@link DltProperties}가 정한다. */
@Configuration(proxyBeanMethods = false)
public class SessionTopicsConfig {

    @Bean
    NewTopic sessionDltTopic(DltProperties dlt, @Value("${curtaincall.topics.session}") String topic) {
        return dltTopic(dlt.dltTopic(topic));
    }

    @Bean
    NewTopic orderDltTopic(DltProperties dlt, @Value("${curtaincall.topics.order}") String topic) {
        return dltTopic(dlt.dltTopic(topic));
    }

    @Bean
    NewTopic availabilityDltTopic(DltProperties dlt, @Value("${curtaincall.topics.reservation-session}") String topic) {
        return dltTopic(dlt.dltTopic(topic));
    }

    private static NewTopic dltTopic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }
}
