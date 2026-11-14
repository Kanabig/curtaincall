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
    public void inquiryMissed(Instant now, Instant nextAt, Instant deadline) {
        this.inquiryAttempts++;
        this.updatedAt = now;
        this.nextInquiryAt = nextAt.isAfter(deadline) ? null : nextAt;
    }

    public boolean isInquiryExhausted() {
        return status == PaymentStatus.UNKNOWN && nextInquiryAt == null;
    }

    private void startInquiry(Instant now, Duration firstInquiryDelay) {
        this.inquiryStartedAt = now;
        this.inquiryAttempts = 0;
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
