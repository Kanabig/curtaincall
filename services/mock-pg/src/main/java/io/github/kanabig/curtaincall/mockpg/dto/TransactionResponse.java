package io.github.kanabig.curtaincall.mockpg.dto;

import io.github.kanabig.curtaincall.mockpg.entity.Transaction;
import io.github.kanabig.curtaincall.mockpg.entity.TransactionStatus;
import java.time.Instant;

public record TransactionResponse(
        String tid,
        String merchantOrderId,
        TransactionStatus status,
        long amount,
        String failureCode,
        Instant createdAt,
        Instant cancelledAt) {

    /** 거래 없이 취소만 기록된 주문. tid가 없다. */
    public static TransactionResponse preCancelled(String merchantOrderId, Instant now) {
        return new TransactionResponse(null, merchantOrderId, TransactionStatus.CANCELLED, 0, null, null, now);
    }

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getTid(),
                transaction.getMerchantOrderId(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getFailureCode(),
                transaction.getCreatedAt(),
                transaction.getCancelledAt());
    }
}
