package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.payment.config.PaymentProperties;
import io.github.kanabig.curtaincall.payment.entity.FailureReason;
import io.github.kanabig.curtaincall.payment.entity.Payment;
import io.github.kanabig.curtaincall.payment.entity.PaymentStatus;
import io.github.kanabig.curtaincall.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 호출이나 거래 조회가 끝난 뒤 결과를 저장하고 응답 이벤트를 기록한다. 행을 잠그고 아직 결정되지 않은 상태에서만 바꾸므로, 같은 결제의 결과가 두 번
 * 들어와도 이벤트는 한 번만 나간다.
 */
@Service
@Transactional(readOnly = true)
public class PaymentResultService {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultService.class);

    private final PaymentRepository repository;
    private final PaymentEvents events;
    private final PaymentMetrics metrics;
    private final PaymentProperties.Inquiry inquiry;
    private final Clock clock;

    public PaymentResultService(
            PaymentRepository repository,
            PaymentEvents events,
            PaymentMetrics metrics,
            PaymentProperties properties,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.metrics = metrics;
        this.inquiry = properties.inquiry();
        this.clock = clock;
    }

    @Transactional
    public void approved(String paymentId, String pgTid, Instant approvedAt) {
        Payment payment = lock(paymentId);
        if (payment.approve(pgTid, approvedAt, clock.instant())) {
            events.approved(payment);
        } else {
            log.info("이미 결정된 결제라 승인 결과를 무시한다: paymentId={}, status={}", paymentId, payment.getStatus());
        }
    }

    @Transactional
    public void failed(String paymentId, FailureReason reason) {
        Payment payment = lock(paymentId);
        if (payment.fail(reason, clock.instant())) {
            events.failed(payment);
        } else {
            log.info("이미 결정된 결제라 실패 결과를 무시한다: paymentId={}, status={}", paymentId, payment.getStatus());
        }
    }

    /** 승인 호출의 결과를 모른다. 실패로 바꾸지 않고 거래 조회를 예약한다. */
    @Transactional
    public void unknown(String paymentId) {
        Payment payment = lock(paymentId);
        if (payment.markUnknown(clock.instant(), inquiry.initialDelay())) {
            log.warn("승인 결과를 몰라 UNKNOWN으로 두고 거래 조회를 예약한다: paymentId={}", paymentId);
        }
    }

    /** 거래 조회로도 결과를 얻지 못했다. 다음 조회를 예약하고, 제한 시간을 넘기면 UNKNOWN으로 남긴다. */
    @Transactional
    public void inquiryMissed(String paymentId) {
        Payment payment = lock(paymentId);
        Instant now = clock.instant();
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return;
        }
        Instant deadline = payment.getInquiryStartedAt().plus(inquiry.maxDuration());
        Instant next = now.plus(inquiry.delayAfter(payment.getInquiryAttempts() + 1));
        payment.inquiryMissed(now, next, deadline);
        if (payment.isInquiryExhausted()) {
            onInquiryExhausted(payment);
        }
    }

    /**
     * 거래 조회 제한 시간 안에 결과를 얻지 못했을 때의 처리. UNKNOWN으로 남기고 메트릭만 올린다. 소진 정책(계속 UNKNOWN으로 둘지 확정할지)은 이 메서드 한 곳에서
     * 바꾼다.
     */
    private void onInquiryExhausted(Payment payment) {
        metrics.unknownExhausted();
        log.error(
                "거래 조회 제한 시간 안에 결과를 얻지 못했다. UNKNOWN으로 남긴다: paymentId={}, orderId={}, attempts={}",
                payment.getPaymentId(),
                payment.getOrderId(),
                payment.getInquiryAttempts());
    }

    private Payment lock(String paymentId) {
        return repository
                .findForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없다: " + paymentId));
    }
}
