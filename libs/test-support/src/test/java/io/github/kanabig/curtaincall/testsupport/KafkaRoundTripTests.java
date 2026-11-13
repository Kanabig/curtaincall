package io.github.kanabig.curtaincall.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest
@Import({TestContainersConfig.class, KafkaRoundTripTests.SampleConsumer.class})
class KafkaRoundTripTests {

    static final String TOPIC = "sample.round-trip";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    SampleConsumer consumer;

    @Test
    @DisplayName("apache/kafka 4.3 브로커에 발행한 메시지를 컨슈머가 받는다")
    void publishAndConsume() throws Exception {
        kafkaTemplate.send(TOPIC, "key-1", "hello").get(10, TimeUnit.SECONDS);

        String received = consumer.messages.poll(15, TimeUnit.SECONDS);

        assertThat(received).isEqualTo("hello");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SampleConsumer {

        final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

        @Bean
        NewTopic sampleTopic() {
            return TopicBuilder.name(TOPIC).partitions(1).replicas(1).build();
        }

        @KafkaListener(topics = TOPIC)
        void onMessage(String message) {
            messages.add(message);
        }
    }
}
