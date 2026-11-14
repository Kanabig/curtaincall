package io.github.kanabig.curtaincall.outbox.relay;

import io.github.kanabig.curtaincall.common.web.trace.RequestIdContext;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelopes;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [NFR-4] 도메인 변경과 같은 트랜잭션에서 outbox에 행을 넣는다. 트랜잭션 밖에서 부르면 커밋과 발행의 원자성이 깨지므로 예외를 던진다.
 */
public class OutboxWriter {

    private final OutboxRepository repository;
    private final EventEnvelopes envelopes;

    public OutboxWriter(OutboxRepository repository, EventEnvelopes envelopes) {
        this.repository = repository;
        this.envelopes = envelopes;
    }

    /**
     * @param topic 발행할 토픽
     * @param aggregateId 메시지 키. 같은 키는 같은 파티션으로 가서 순서가 지켜진다
     * @param eventType 이벤트 이름(버전 규칙은 {@code EventTypes})
     * @param payload 봉투의 payload가 될 객체
     * @return 이벤트 ID. 소비 측 멱등 처리에 쓰인다
     */
    public String append(String topic, String aggregateId, String eventType, Object payload) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("outbox는 도메인 변경과 같은 트랜잭션 안에서만 기록한다");
        }
        String eventId = UUID.randomUUID().toString();
        String envelope = envelopes.write(eventId, eventType, aggregateId, payload);
        repository.insert(topic, aggregateId, eventId, eventType, envelope, RequestIdContext.current());
        return eventId;
    }
}
