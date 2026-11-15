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
    private final PaymentProperties.Cancel cancel;
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
        this.cancel = properties.cancel();
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

    /** 거래 조회로도 결과를 얻지 못했을 때 다음에 할 일. */
    public enum InquiryMiss {
        /** 다음 조회가 예약됐다. */
        CONTINUE,
        /** 제한 시간을 넘겼고 조회가 내내 404였다. 망취소 대상이다. */
        EXHAUSTED_ALL_NOT_FOUND,
        /** 제한 시간을 넘겼고 오류가 섞였다. 결과를 알 수 없으므로 UNKNOWN으로 남긴다. */
        EXHAUSTED_UNKNOWN
    }

    /**
     * 거래 조회로도 결과를 얻지 못했다. 다음 조회를 예약하고, 제한 시간을 넘기면 소진 처리를 한다.
     *
     * @param notFound PG가 404("거래 없음")로 답했으면 true, 오류나 예상 밖 응답이면 false
     */
    @Transactional
    public InquiryMiss inquiryMissed(String paymentId, boolean notFound) {
        Payment payment = lock(paymentId);
        Instant now = clock.instant();
        if (payment.getStatus() != PaymentStatus.UNKNOWN) {
            return InquiryMiss.CONTINUE;
        }
        Instant deadline = payment.getInquiryStartedAt().plus(inquiry.maxDuration());
        Instant next = now.plus(inquiry.delayAfter(payment.getInquiryAttempts() + 1));
        payment.inquiryMissed(now, next, deadline, notFound);
        return payment.isInquiryExhausted() ? onInquiryExhausted(payment) : InquiryMiss.CONTINUE;
    }

    /**
     * 거래 조회 제한 시간 안에 결과를 얻지 못했을 때의 처리 정책. 내내 404였으면 망취소로 실패를 확정하게 하고, 오류가 섞였으면 UNKNOWN으로 남기고 메트릭을 올린다.
     */
    private InquiryMiss onInquiryExhausted(Payment payment) {
        if (payment.isInquiryAllNotFound()) {
            log.warn(
                    "거래 조회가 끝까지 거래 없음이었다. 망취소로 실패를 확정한다: paymentId={}, orderId={}",
                    payment.getPaymentId(),
                    payment.getOrderId());
            return InquiryMiss.EXHAUSTED_ALL_NOT_FOUND;
        }
        metrics.unknownExhausted();
        log.error(
                "거래 조회 제한 시간 안에 결과를 얻지 못했다. UNKNOWN으로 남긴다: paymentId={}, orderId={}, attempts={}",
                payment.getPaymentId(),
                payment.getOrderId(),
                payment.getInquiryAttempts());
        return InquiryMiss.EXHAUSTED_UNKNOWN;
    }

    /** 망취소에 실패했다. 실패로 확정하지 못하므로 UNKNOWN으로 남기고 메트릭을 올린다. */
    public void netCancelFailed(String paymentId) {
        metrics.unknownExhausted();
        log.error("망취소에 실패했다. UNKNOWN으로 남긴다: paymentId={}", paymentId);
    }

    /** PG 취소가 확인됐다. 이미 끝난 건이면 이벤트를 다시 내지 않는다. */
    @Transactional
    public void cancelled(String paymentId) {
        Payment payment = lock(paymentId);
        if (payment.cancelled(clock.instant())) {
            events.cancelled(payment);
        } else {
            log.info("취소 중이 아니라 취소 결과를 무시한다: paymentId={}, status={}", paymentId, payment.getStatus());
        }
    }

    /** PG 취소를 확인하지 못했다. 다음 시도를 예약하고, 한도를 넘으면 CANCEL_FAILED로 두고 메트릭을 올린다. 응답은 보내지 않는다. */
    @Transactional
    public void cancelMissed(String paymentId) {
        Payment payment = lock(paymentId);
        Instant now = clock.instant();
        Instant next = now.plus(cancel.delayAfter(payment.getCancelAttempts() + 1));
        if (payment.cancelMissed(now, next, cancel.maxAttempts())) {
            metrics.cancelFailed();
            log.error(
                    "PG 취소가 재시도 한도를 넘었다. 수동 처리가 필요하다: paymentId={}, orderId={}, attempts={}",
                    paymentId,
                    payment.getOrderId(),
                    payment.getCancelAttempts());
        }
    }

    private Payment lock(String paymentId) {
        return repository
                .findForUpdate(paymentId)
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없다: " + paymentId));
    }
}
