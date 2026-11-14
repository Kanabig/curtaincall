package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.payment.config.PaymentTopicsConfig;
import io.github.kanabig.curtaincall.payment.dto.PaymentApprovedEvent;
import io.github.kanabig.curtaincall.payment.dto.PaymentFailedEvent;
import io.github.kanabig.curtaincall.payment.entity.Payment;
import org.springframework.stereotype.Component;

/** [PAY-6] 결제 결과를 도메인 변경과 같은 트랜잭션에서 outbox에 기록한다. 트랜잭션 밖에서 부르면 예외다. */
@Component
class PaymentEvents {

    private final OutboxWriter outboxWriter;

    PaymentEvents(OutboxWriter outboxWriter) {
        this.outboxWriter = outboxWriter;
    }

    void approved(Payment payment) {
        outboxWriter.append(
                PaymentTopicsConfig.PAYMENT_TOPIC,
                payment.getOrderId(),
                "PaymentApproved",
                PaymentApprovedEvent.from(payment));
    }

    void failed(Payment payment) {
        outboxWriter.append(
                PaymentTopicsConfig.PAYMENT_TOPIC,
                payment.getOrderId(),
                "PaymentFailed",
                PaymentFailedEvent.from(payment));
    }
}
