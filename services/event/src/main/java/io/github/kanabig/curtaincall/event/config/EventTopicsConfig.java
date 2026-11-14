package io.github.kanabig.curtaincall.event.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** event가 소유하는 토픽. 회차 이벤트의 스키마와 발행은 event 책임이다(spec/events.md). */
@Configuration
public class EventTopicsConfig {

    public static final String SESSION_TOPIC = "event.session";

    @Bean
    NewTopic sessionTopic() {
        return TopicBuilder.name(SESSION_TOPIC).partitions(3).replicas(1).build();
    }
}
