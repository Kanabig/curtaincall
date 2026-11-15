package io.github.kanabig.curtaincall.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** [PAY-3] 주문당 하나(order_id UNIQUE). 상태 전이는 이 클래스의 메서드로만 하고, 허용되지 않는 전이는 false를 돌려준다. */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @Column(name = "payment_id", length = 40)
    private String paymentId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private String orderId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(nullable = false, updatable = false)
    private long amount;

    @Column(name = "card_token", nullable = false, updatable = false)
    private String cardToken;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "pg_tid")
    private String pgTid;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_reason")
    private FailureReason failureReason;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "inquiry_started_at")
    private Instant inquiryStartedAt;

    /** null이면 거래 조회 대상이 아니다(UNKNOWN이 아니거나, 조회 제한 시간을 넘겨 멈춘 상태). */
    @Column(name = "next_inquiry_at")
    private Instant nextInquiryAt;

    @Column(name = "inquiry_attempts", nullable = false)
    private int inquiryAttempts;

    @Column(name = "inquiry_error_seen", nullable = false)
    private boolean inquiryErrorSeen;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cancel_reason")
    private CancelReason cancelReason;

    @Column(name = "cancel_attempts", nullable = false)
    private int cancelAttempts;

    /** CANCELLING일 때 다음 PG 취소 시도 시각. 처리 중에는 임대 기간만큼 뒤로 밀려 있다. */
    @Column(name = "next_cancel_at")
    private Instant nextCancelAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Payment() {}

    public static Payment request(String orderId, String userId, long amount, String cardToken, Instant now) {
        Payment payment = new Payment();
        payment.paymentId = "pay_" + UUID.randomUUID().toString().replace("-", "");
        payment.orderId = orderId;
        payment.userId = userId;
        payment.amount = amount;
        payment.cardToken = cardToken;
        payment.status = PaymentStatus.REQUESTED;
        payment.createdAt = now;
        payment.updatedAt = now;
        return payment;
    }

    public boolean approve(String pgTid, Instant approvedAt, Instant now) {
        if (!isUndecided()) {
            return false;
        }
        this.status = PaymentStatus.APPROVED;
        this.pgTid = pgTid;
        this.approvedAt = approvedAt;
        this.nextInquiryAt = null;
        this.updatedAt = now;
        return true;
    }

    public boolean fail(FailureReason reason, Instant now) {
        if (!isUndecided()) {
            return false;
        }
        this.status = PaymentStatus.FAILED;
        this.failureReason = reason;
        this.nextInquiryAt = null;
        this.updatedAt = now;
        return true;
    }

    /**
     * 환불을 시작한다(PAY-5). 승인된 결제와 수동 처리 대상(CANCEL_FAILED)에서만 시작할 수 있다. 시도 횟수를 0으로 되돌리고, 다음 시도 시각은 임대 기간 뒤로 둔다. 바로 시도하는
     * 호출이 따로 있고, 그 호출이 사라져도 임대가 끝나면 스케줄러가 이어받는다.
     */
    public boolean startCancel(CancelReason reason, Instant now, Duration lease) {
        if (status != PaymentStatus.APPROVED && status != PaymentStatus.CANCEL_FAILED) {
            return false;
        }
        this.status = PaymentStatus.CANCELLING;
        this.cancelReason = reason;
        this.cancelAttempts = 0;
        this.nextCancelAt = now.plus(lease);
        this.updatedAt = now;
        return true;
    }

    /** 다음 시도 예약이 사라진 CANCELLING을 다시 시작한다. 시도 횟수는 이어 간다. */
    public boolean restartCancel(Instant now, Duration lease) {
        if (status != PaymentStatus.CANCELLING || nextCancelAt != null) {
            return false;
        }
        this.nextCancelAt = now.plus(lease);
        this.updatedAt = now;
        return true;
    }

    public boolean cancelled(Instant now) {
        if (status != PaymentStatus.CANCELLING) {
            return false;
        }
        this.status = PaymentStatus.CANCELLED;
        this.cancelledAt = now;
        this.nextCancelAt = null;
        this.updatedAt = now;
        return true;
    }

    /** 취소 대상으로 뽑은 뒤 다른 인스턴스가 같은 건을 집지 않도록 다음 시도 시각을 임대 기간만큼 미룬다. */
    public void leaseCancel(Instant until) {
        this.nextCancelAt = until;
    }

    /**
     * 취소를 확인하지 못했다. 시도 횟수를 올리고, 한도에 닿으면 CANCEL_FAILED로 두고 true를 돌려준다. 아니면 다음 시도를 예약한다.
     */
    public boolean cancelMissed(Instant now, Instant nextAt, int maxAttempts) {
        if (status != PaymentStatus.CANCELLING) {
            return false;
        }
        this.cancelAttempts++;
        this.updatedAt = now;
        if (cancelAttempts >= maxAttempts) {
            this.status = PaymentStatus.CANCEL_FAILED;
            this.nextCancelAt = null;
            return true;
        }
        this.nextCancelAt = nextAt;
        return false;
    }

    /** 승인 호출의 결과를 모를 때. REQUESTED에서만 UNKNOWN이 되고 거래 조회를 예약한다. */
    public boolean markUnknown(Instant now, Duration firstInquiryDelay) {
        if (status != PaymentStatus.REQUESTED) {
            return false;
        }
        this.status = PaymentStatus.UNKNOWN;
        startInquiry(now, firstInquiryDelay);
        return true;
    }

    /**
     * 명령 재전송 때문에 거래 조회를 처음부터 다시 한다(PAY-4). 조회가 진행 중이면 다시 시작하지 않는다. 재전송이 계속 오는 동안 조회 시작 시각이 밀리면 조회 제한 시간이 끝나지
     * 않아 망취소로 넘어가지 못한다. 조회를 소진한 UNKNOWN만 다시 시작한다.
     */
    public boolean restartInquiry(Instant now, Duration firstInquiryDelay) {
        if (status != PaymentStatus.UNKNOWN || nextInquiryAt != null) {
            return false;
        }
        startInquiry(now, firstInquiryDelay);
        return true;
    }

    /** 조회 대상으로 뽑은 뒤 다른 인스턴스가 같은 건을 집지 않도록 다음 조회 시각을 임대 기간만큼 미룬다. */
    public void leaseInquiry(Instant until) {
        this.nextInquiryAt = until;
    }

    /** 조회했지만 결과를 얻지 못했다. 다음 조회를 예약하고, 제한 시간을 넘기면 조회를 멈춘다. */
    public void inquiryMissed(Instant now, Instant nextAt, Instant deadline, boolean notFound) {
        this.inquiryAttempts++;
        if (!notFound) {
            this.inquiryErrorSeen = true;
        }
        this.updatedAt = now;
        this.nextInquiryAt = nextAt.isAfter(deadline) ? null : nextAt;
    }

    /** 지금까지의 조회가 모두 "거래 없음"(404)이었는지. */
    public boolean isInquiryAllNotFound() {
        return !inquiryErrorSeen;
    }

    public boolean isInquiryExhausted() {
        return status == PaymentStatus.UNKNOWN && nextInquiryAt == null;
    }

    private void startInquiry(Instant now, Duration firstInquiryDelay) {
        this.inquiryStartedAt = now;
        this.inquiryAttempts = 0;
        this.inquiryErrorSeen = false;
        this.nextInquiryAt = now.plus(firstInquiryDelay);
        this.updatedAt = now;
    }

    private boolean isUndecided() {
        return status == PaymentStatus.REQUESTED || status == PaymentStatus.UNKNOWN;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getUserId() {
        return userId;
    }

    public long getAmount() {
        return amount;
    }

    public String getCardToken() {
        return cardToken;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getPgTid() {
        return pgTid;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public CancelReason getCancelReason() {
        return cancelReason;
    }

    public int getCancelAttempts() {
        return cancelAttempts;
    }

    public Instant getNextCancelAt() {
        return nextCancelAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getInquiryStartedAt() {
        return inquiryStartedAt;
    }

    public Instant getNextInquiryAt() {
        return nextInquiryAt;
    }

    public int getInquiryAttempts() {
        return inquiryAttempts;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
