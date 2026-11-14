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
