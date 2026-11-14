package io.github.kanabig.curtaincall.payment.dto;

import io.github.kanabig.curtaincall.payment.entity.FailureReason;
import io.github.kanabig.curtaincall.payment.entity.Payment;

/** {@code payment.payment}의 {@code PaymentFailed} payload. */
public record PaymentFailedEvent(String paymentId, String orderId, FailureReason reason) {

    public static PaymentFailedEvent from(Payment payment) {
        return new PaymentFailedEvent(payment.getPaymentId(), payment.getOrderId(), payment.getFailureReason());
    }
}
