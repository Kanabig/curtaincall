package io.github.kanabig.curtaincall.payment.dto;

import io.github.kanabig.curtaincall.payment.entity.Payment;
import java.time.Instant;

/** {@code payment.payment}의 {@code PaymentApproved} payload. */
public record PaymentApprovedEvent(String paymentId, String orderId, long amount, Instant approvedAt) {

    public static PaymentApprovedEvent from(Payment payment) {
        return new PaymentApprovedEvent(
                payment.getPaymentId(), payment.getOrderId(), payment.getAmount(), payment.getApprovedAt());
    }
}
