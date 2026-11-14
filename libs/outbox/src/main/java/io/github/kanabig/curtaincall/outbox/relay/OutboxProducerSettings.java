package io.github.kanabig.curtaincall.outbox.relay;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;

/**
 * 프로듀서 설정. {@code acks=all}과 {@code enable.idempotence=true}는 kafka-clients 3.x 기본값이지만 명시하고, {@code
 * spring.kafka.producer.*}에 다른 값이 있어도 덮어쓴다. {@code acks=1} 같은 충돌 설정이 들어오면 클라이언트가 멱등성을 조용히 꺼 버리기 때문이다.
 */
public final class OutboxProducerSettings {

    private OutboxProducerSettings() {}

    /** 접속 정보는 {@link KafkaConnectionDetails}에서 가져온다. 테스트의 서비스 연결처럼 properties 밖에서 오는 값을 반영하기 위해서다. */
    public static Map<String, Object> from(
            KafkaProperties kafkaProperties, KafkaConnectionDetails connectionDetails, Duration sendTimeout) {
        Map<String, Object> properties = kafkaProperties.buildProducerProperties(null);
        properties.put(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                connectionDetails.getProducer().getBootstrapServers());
        return enforce(properties, sendTimeout);
    }

    /**
     * 멱등성 설정과 발송 시간 제한을 고정한다. 발송 시간 제한은 릴레이 트랜잭션이 행 락과 DB 커넥션을 쥔 시간의 상한이라 {@code
     * spring.kafka.producer.*}로 늘리지 못하게 덮어쓴다. {@code send()}는 토픽 메타데이터가 없으면 future를 돌려주기 전에 {@code
     * max.block.ms}만큼 막히므로 릴레이의 {@code future.get(timeout)}만으로는 상한이 되지 않는다.
     */
    public static Map<String, Object> enforce(Map<String, Object> properties, Duration sendTimeout) {
        Map<String, Object> result = new HashMap<>(properties);
        result.put(ProducerConfig.ACKS_CONFIG, "all");
        result.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // 멱등성과 충돌하는 값(retries=0, in-flight 5 초과)은 제거해 기본값을 쓴다
        result.remove(ProducerConfig.RETRIES_CONFIG);
        result.remove(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION);
        int timeoutMs = Math.toIntExact(sendTimeout.toMillis());
        result.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, timeoutMs);
        // delivery.timeout.ms >= linger.ms + request.timeout.ms 여야 KafkaProducer가 만들어진다
        result.put(ProducerConfig.LINGER_MS_CONFIG, 0);
        result.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs / 2);
        result.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, timeoutMs);
        result.put(
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        result.put(
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                org.apache.kafka.common.serialization.StringSerializer.class);
        return result;
    }
}
