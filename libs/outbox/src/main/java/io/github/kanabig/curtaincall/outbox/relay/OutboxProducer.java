package io.github.kanabig.curtaincall.outbox.relay;

import java.util.Map;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 릴레이와 DLT 발행이 쓰는 프로듀서. {@code KafkaTemplate}을 빈으로 직접 등록하면 Boot가 서비스용 기본 {@code KafkaTemplate}을 만들지 않으므로 한 겹
 * 감싼다.
 */
public class OutboxProducer {

    private final KafkaTemplate<String, String> template;

    public OutboxProducer(Map<String, Object> producerProperties) {
        this.template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProperties));
    }

    public KafkaTemplate<String, String> template() {
        return template;
    }

    public void close() {
        template.destroy();
    }
}
