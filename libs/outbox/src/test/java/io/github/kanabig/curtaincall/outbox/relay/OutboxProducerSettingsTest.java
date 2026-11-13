package io.github.kanabig.curtaincall.outbox.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

class OutboxProducerSettingsTest {

    @Test
    @DisplayName("kafka-clients 기본값은 acks=all, enable.idempotence=true다")
    void clientDefaults() {
        Map<String, Object> defaults = ProducerConfig.configDef().defaultValues();

        assertThat(defaults.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        assertThat(defaults.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isEqualTo(true);
    }

    @Test
    @DisplayName("spring.kafka.producer를 설정하지 않으면 Boot는 해당 키를 넣지 않아 클라이언트 기본값이 적용된다")
    void bootDoesNotSetUnconfiguredKeys() {
        Map<String, Object> boot = new KafkaProperties().buildProducerProperties(null);

        assertThat(boot).doesNotContainKeys(ProducerConfig.ACKS_CONFIG, ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG);
    }

    @Test
    @DisplayName("spring.kafka.producer.acks=1 이 들어와도 acks=all, 멱등성 true로 덮어쓴다")
    void conflictingSettingsAreOverridden() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        kafkaProperties.getProducer().setAcks("1");
        kafkaProperties.getProducer().setRetries(0);
        kafkaProperties.getProducer().getProperties().put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "10");
        Map<String, Object> boot = kafkaProperties.buildProducerProperties(null);
        assertThat(boot.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("1");

        Map<String, Object> enforced = OutboxProducerSettings.enforce(boot);
        ProducerConfig config = new ProducerConfig(enforced);

        assertThat(enforced.get(ProducerConfig.ACKS_CONFIG)).isEqualTo("all");
        // 클라이언트는 all을 내부 값 -1로 바꿔 읽는다
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getInt(ProducerConfig.RETRIES_CONFIG)).isGreaterThan(0);
        assertThat(config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION))
                .isLessThanOrEqualTo(5);
    }
}
