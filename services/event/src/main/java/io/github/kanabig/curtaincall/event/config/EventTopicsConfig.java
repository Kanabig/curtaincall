package io.github.kanabig.curtaincall.event.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** event가 소유하는 토픽. 회차 이벤트의 스키마와 발행은 event 책임이다(spec/events.md). */
@Configuration
public class EventTopicsConfig {

    public static final String SESSION_TOPIC = "event.session";

    /**
     * 키(sessionId)별 마지막 이벤트를 보존하도록 compaction을 쓴다. queue의 Redis conf는 이벤트에 실린 전체 값으로 덮어써 만들어서, Redis를 잃어도 컨슈머
     * 그룹 오프셋을 처음으로 돌리면 복구된다. 기본 보존 기간(7일)이 지나면 오래된 회차의 이벤트가 사라져 복구할 수 없다. compaction 뒤에는 같은 회차의 SessionCreated가 이후 SessionUpdated로 대체될 수 있다. 두 이벤트 모두 전체 값을 싣고, reservation은 SessionUpdated도 재고 등록 경로로 처리하므로 처음부터 읽어도 복구된다.
     */
    @Bean
    NewTopic sessionTopic() {
        return TopicBuilder.name(SESSION_TOPIC)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT)
                .build();
    }
}
