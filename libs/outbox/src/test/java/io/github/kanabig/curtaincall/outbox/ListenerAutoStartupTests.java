package io.github.kanabig.curtaincall.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.context.TestPropertySource;

/** 팩토리를 직접 만들어도 spring.kafka.listener.auto-startup이 적용돼야 한다. */
@TestPropertySource(properties = "spring.kafka.listener.auto-startup=false")
class ListenerAutoStartupTests extends OutboxIntegrationTest {

    @Autowired
    KafkaListenerEndpointRegistry registry;

    @Test
    @DisplayName("auto-startup=false면 리스너 컨테이너가 시작되지 않는다")
    void listenersStayStopped() {
        assertThat(registry.getListenerContainers()).isNotEmpty().noneMatch(container -> container.isRunning());
    }
}
