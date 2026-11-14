package io.github.kanabig.curtaincall.payment.dto;

/** {@code payment.command}의 {@code ApprovePayment} payload(spec/events.md). */
public record ApprovePaymentCommand(String orderId, String userId, long amount, String cardToken) {}
