package io.github.kanabig.curtaincall.payment.dto;

import io.github.kanabig.curtaincall.payment.entity.CancelReason;
import io.github.kanabig.curtaincall.payment.entity.Payment;

/** {@code payment.payment}의 {@code PaymentCancelled} payload. */
public record PaymentCancelledEvent(String paymentId, String orderId, long amount, CancelReason reason) {

    public static PaymentCancelledEvent from(Payment payment) {
        return new PaymentCancelledEvent(
                payment.getPaymentId(), payment.getOrderId(), payment.getAmount(), payment.getCancelReason());
    }
}
