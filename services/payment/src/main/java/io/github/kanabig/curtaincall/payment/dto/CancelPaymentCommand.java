package io.github.kanabig.curtaincall.payment.dto;

import io.github.kanabig.curtaincall.payment.entity.CancelReason;

/** {@code payment.command}의 {@code CancelPayment} payload(spec/events.md). */
public record CancelPaymentCommand(String orderId, String paymentId, long amount, CancelReason reason) {}
