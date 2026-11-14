package io.github.kanabig.curtaincall.mockpg.entity;

import java.time.Instant;

/** PG 쪽 거래. 상태는 메모리에만 있고 재시작하면 사라진다. */
public class Transaction {

    private final String tid;
    private final String merchantOrderId;
    private final long amount;
    private final String failureCode;
    private final Instant createdAt;
    private volatile TransactionStatus status;
    private volatile Instant cancelledAt;

    private Transaction(
            String tid,
            String merchantOrderId,
            long amount,
            TransactionStatus status,
            String failureCode,
            Instant now) {
        this.tid = tid;
        this.merchantOrderId = merchantOrderId;
        this.amount = amount;
        this.status = status;
        this.failureCode = failureCode;
        this.createdAt = now;
    }

    public static Transaction approved(String tid, String merchantOrderId, long amount, Instant now) {
        return new Transaction(tid, merchantOrderId, amount, TransactionStatus.APPROVED, null, now);
    }

    public static Transaction declined(
            String tid, String merchantOrderId, long amount, String failureCode, Instant now) {
        return new Transaction(tid, merchantOrderId, amount, TransactionStatus.DECLINED, failureCode, now);
    }

    /** 이미 취소된 거래면 아무것도 바꾸지 않는다(멱등). 승인 거래가 아니면 false. */
    public synchronized boolean cancel(Instant now) {
        if (status == TransactionStatus.CANCELLED) {
            return true;
        }
        if (status != TransactionStatus.APPROVED) {
            return false;
        }
        status = TransactionStatus.CANCELLED;
        cancelledAt = now;
        return true;
    }

    public String getTid() {
        return tid;
    }

    public String getMerchantOrderId() {
        return merchantOrderId;
    }

    public long getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
