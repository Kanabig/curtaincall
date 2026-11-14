package io.github.kanabig.curtaincall.mockpg.dto;

import io.github.kanabig.curtaincall.mockpg.entity.Transaction;
import io.github.kanabig.curtaincall.mockpg.entity.TransactionStatus;
import java.time.Instant;

/** duplicate가 true면 같은 merchantOrderId로 이미 처리된 첫 거래를 돌려준 것이다. */
public record ApprovalResponse(
        String tid,
        String merchantOrderId,
        TransactionStatus status,
        long amount,
        String failureCode,
        Instant createdAt,
        boolean duplicate) {

    public static ApprovalResponse of(Transaction transaction, boolean duplicate) {
        return new ApprovalResponse(
                transaction.getTid(),
                transaction.getMerchantOrderId(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getFailureCode(),
                transaction.getCreatedAt(),
                duplicate);
    }
}
