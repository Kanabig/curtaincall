package io.github.kanabig.curtaincall.outbox.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code curtaincall.outbox.*} 설정. 발행(outbox)과 소비(consumer)는 각각 켠다. 소비만 하는 서비스는 outbox 테이블이 없다.
 */
@ConfigurationProperties("curtaincall.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Relay relay,
        @DefaultValue Consumer consumer) {

    public record Relay(
            @DefaultValue("true") boolean autoStart,
            @DefaultValue("200ms") Duration pollInterval,
            @DefaultValue("100") int batchSize,
            @DefaultValue("5s") Duration sendTimeout,
            @DefaultValue("1d") Duration retention,
            @DefaultValue("1m") Duration cleanupInterval) {}

    public record Consumer(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("1s") Duration backoffInitial,
            @DefaultValue("2.0") double backoffMultiplier,
            @DefaultValue("3") int maxRetries,
            @DefaultValue("7d") Duration processedEventRetention,
            @DefaultValue(".DLT") String dltSuffix,
            @DefaultValue Map<String, String> dltTopics) {

        /** 토픽의 DLT 이름. dltTopics에 있으면 그 이름, 없으면 {@code 토픽 + dltSuffix}. 같은 토픽을 여러 서비스가 읽는 경우만 덮어쓴다. */
        public String dltTopic(String topic) {
            String named = dltTopics.get(topic);
            return named != null ? named : topic + dltSuffix;
        }
    }
}
