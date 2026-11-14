package io.github.kanabig.curtaincall.outbox.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.consumer.IdempotentConsumer;
import io.github.kanabig.curtaincall.outbox.consumer.OutboxConsumerErrorHandling;
import io.github.kanabig.curtaincall.outbox.consumer.ProcessedEventMaintenance;
import io.github.kanabig.curtaincall.outbox.consumer.ProcessedEventRepository;
import io.github.kanabig.curtaincall.outbox.consumer.RequestIdRecordInterceptor;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import io.github.kanabig.curtaincall.outbox.relay.OutboxMaintenance;
import io.github.kanabig.curtaincall.outbox.relay.OutboxProducer;
import io.github.kanabig.curtaincall.outbox.relay.OutboxProducerSettings;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRelay;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRelayLifecycle;
import io.github.kanabig.curtaincall.outbox.relay.OutboxRepository;
import io.github.kanabig.curtaincall.outbox.relay.OutboxTransactions;
import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.outbox.relay.PeriodicTaskLifecycle;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 발행({@code curtaincall.outbox.enabled})과 소비({@code curtaincall.outbox.consumer.enabled})를 각각 켠다.
 *
 * <p>서비스 적용 방법
 *
 * <ul>
 *   <li>스키마: 이 모듈의 {@code outbox/outbox.sql}, {@code outbox/processed_event.sql}을 서비스의 Flyway 마이그레이션({@code
 *       V<n>__...sql})으로 복사한다. 서비스가 자기 스키마의 버전을 관리하므로 모듈이 마이그레이션을 직접 싣지 않는다.
 *   <li>발행: 도메인 변경과 같은 트랜잭션에서 {@code OutboxWriter.append}를 부른다. 릴레이는 자동으로 돈다.
 *   <li>소비: 리스너에 {@code containerFactory = LISTENER_CONTAINER_FACTORY}를 주고, {@code IdempotentConsumer.consume}으로
 *       처리한다.
 *   <li>토픽: 소유 서비스가 {@code NewTopic}으로 선언한다. 소비 서비스는 자기가 읽는 토픽의 {@code .DLT}도 선언해야 한다(브로커 자동 생성은 꺼져 있다).
 * </ul>
 */
@AutoConfiguration(
        after = {JdbcTemplateAutoConfiguration.class, KafkaAutoConfiguration.class},
        afterName = "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@EnableConfigurationProperties({OutboxProperties.class, KafkaProperties.class})
@ConditionalOnBean(JdbcTemplate.class)
public class OutboxAutoConfiguration {

    /** 소비 리스너가 {@code containerFactory}로 지정하는 이름. */
    public static final String LISTENER_CONTAINER_FACTORY = "outboxListenerContainerFactory";

    @Bean
    @ConditionalOnMissingBean
    EventEnvelopes eventEnvelopes(ObjectMapper objectMapper) {
        return new EventEnvelopes(objectMapper, Clock.systemDefaultZone());
    }

    /** 릴레이와 DLT 발행이 같이 쓴다. KafkaProducer는 첫 발행 때 만들어지므로 쓰지 않으면 연결하지 않는다. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    OutboxProducer outboxProducer(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails, OutboxProperties properties) {
        return new OutboxProducer(OutboxProducerSettings.from(
                kafkaProperties, connectionDetails, properties.relay().sendTimeout()));
    }

    @Bean
    @ConditionalOnMissingBean
    MeterRegistry outboxMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "curtaincall.outbox", name = "enabled", havingValue = "true")
    static class Publishing {

        @Bean
        OutboxRepository outboxRepository(JdbcTemplate jdbcTemplate) {
            return new OutboxRepository(jdbcTemplate);
        }

        @Bean
        OutboxWriter outboxWriter(OutboxRepository repository, EventEnvelopes envelopes) {
            return new OutboxWriter(repository, envelopes);
        }

        @Bean
        OutboxRelay outboxRelay(
                OutboxRepository repository,
                OutboxProducer outboxProducer,
                PlatformTransactionManager transactionManager,
                OutboxProperties properties,
                MeterRegistry meterRegistry) {
            Gauge.builder("outbox.unpublished", repository, OutboxRepository::countUnpublished)
                    .description("아직 발행하지 못한 outbox 행 수")
                    .register(meterRegistry);
            return new OutboxRelay(
                    repository, outboxProducer.template(), transactionManager, properties.relay(), meterRegistry);
        }

        @Bean
        OutboxMaintenance outboxMaintenance(
                OutboxRepository repository,
                PlatformTransactionManager transactionManager,
                OutboxProperties properties) {
            return new OutboxMaintenance(
                    repository,
                    OutboxTransactions.readCommitted(transactionManager),
                    properties.relay().retention());
        }

        @Bean
        @ConditionalOnProperty(
                prefix = "curtaincall.outbox.relay",
                name = "auto-start",
                havingValue = "true",
                matchIfMissing = true)
        SmartLifecycle outboxRelayLifecycle(
                OutboxRelay relay, OutboxMaintenance maintenance, OutboxProperties properties) {
            return new OutboxRelayLifecycle(relay, maintenance, properties.relay());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "curtaincall.outbox.consumer", name = "enabled", havingValue = "true")
    static class Consuming {

        @Bean
        ProcessedEventRepository processedEventRepository(JdbcTemplate jdbcTemplate) {
            return new ProcessedEventRepository(jdbcTemplate);
        }

        @Bean
        IdempotentConsumer idempotentConsumer(
                EventEnvelopes envelopes,
                ProcessedEventRepository processedEvents,
                PlatformTransactionManager transactionManager,
                MeterRegistry meterRegistry) {
            return new IdempotentConsumer(
                    envelopes, processedEvents, new TransactionTemplate(transactionManager), meterRegistry);
        }

        @Bean
        ProcessedEventMaintenance processedEventMaintenance(
                ProcessedEventRepository repository,
                PlatformTransactionManager transactionManager,
                OutboxProperties properties) {
            return new ProcessedEventMaintenance(
                    repository,
                    OutboxTransactions.readCommitted(transactionManager),
                    properties.consumer().processedEventRetention());
        }

        @Bean
        SmartLifecycle processedEventMaintenanceLifecycle(ProcessedEventMaintenance maintenance) {
            return new PeriodicTaskLifecycle(
                    "processed-event-cleanup", maintenance::deleteExpired, Duration.ofHours(1));
        }

        @Bean(name = LISTENER_CONTAINER_FACTORY)
        ConcurrentKafkaListenerContainerFactory<String, String> outboxListenerContainerFactory(
                OutboxProducer outboxProducer,
                KafkaProperties kafkaProperties,
                KafkaConnectionDetails connectionDetails,
                OutboxProperties properties,
                MeterRegistry meterRegistry) {
            ConcurrentKafkaListenerContainerFactory<String, String> factory =
                    new ConcurrentKafkaListenerContainerFactory<>();
            Map<String, Object> consumerProperties = kafkaProperties.buildConsumerProperties(null);
            consumerProperties.put(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                    connectionDetails.getConsumer().getBootstrapServers());
            DefaultKafkaConsumerFactory<String, String> consumerFactory = new DefaultKafkaConsumerFactory<>(
                    consumerProperties, new StringDeserializer(), new StringDeserializer());
            // 소비 lag 등 클라이언트 지표를 Micrometer로 내보낸다
            consumerFactory.addListener(new MicrometerConsumerListener<>(meterRegistry));
            factory.setConsumerFactory(consumerFactory);
            // 처리가 끝난 뒤 IdempotentConsumer가 직접 커밋한다
            factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            factory.setRecordInterceptor(new RequestIdRecordInterceptor());
            factory.setCommonErrorHandler(OutboxConsumerErrorHandling.create(
                    outboxProducer.template(), properties.consumer(), meterRegistry));
            // 팩토리를 직접 만들었으므로 Boot의 spring.kafka.listener.auto-startup을 여기서 반영한다
            factory.setAutoStartup(kafkaProperties.getListener().isAutoStartup());
            return factory;
        }
    }
}
