package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaTemplate;

/** NFR-4 자동 구성. */
class OutboxWiringTests extends OutboxIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("outbox를 켜도 Boot의 기본 KafkaTemplate은 그대로 있다")
    void bootKafkaTemplateRemains() {
        Map<String, KafkaTemplate> templates = context.getBeansOfType(KafkaTemplate.class);

        assertThat(templates).containsOnlyKeys("kafkaTemplate");
    }
}
