package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.payment.client.InquiryOutcome;
import io.github.kanabig.curtaincall.payment.client.PgClient;
import io.github.kanabig.curtaincall.payment.config.PaymentProperties;
import io.github.kanabig.curtaincall.payment.entity.FailureReason;
import io.github.kanabig.curtaincall.payment.entity.Payment;
import io.github.kanabig.curtaincall.payment.entity.PaymentStatus;
import io.github.kanabig.curtaincall.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [PAY-4] UNKNOWN 결제의 PG 거래 조회. 조회 시각이 된 건을 뽑아 임대(lease)를 걸고 조회한다. PG가 404를 주면 결과를 못 얻은 것으로 보고 UNKNOWN을 유지한 채
 * 다음 조회를 예약한다. FAILED로 바꾸지 않는다.
 */
@Service
public class PaymentInquiryService {

    private static final Logger log = LoggerFactory.getLogger(PaymentInquiryService.class);

    private final PaymentRepository repository;
    private final PgClient pgClient;
    private final PaymentResultService results;
    private final ExecutorService pgCallExecutor;
    private final TransactionTemplate transactionTemplate;
    private final PaymentProperties.Inquiry inquiry;
    private final Clock clock;

    public PaymentInquiryService(
            PaymentRepository repository,
            PgClient pgClient,
            PaymentResultService results,
            ExecutorService pgCallExecutor,
            PlatformTransactionManager transactionManager,
            PaymentProperties properties,
            Clock clock) {
        this.repository = repository;
        this.pgClient = pgClient;
        this.results = results;
        this.pgCallExecutor = pgCallExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.inquiry = properties.inquiry();
        this.clock = clock;
    }

    /** 한 번 돈다. 조회할 건을 뽑아 실행기에 맡기고 바로 돌아온다. */
    public void runDueInquiries() {
        recoverStaleRequests();
        List<String> due = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<Payment> payments = repository.claimDueInquiries(now, inquiry.batchSize());
            payments.forEach(payment -> payment.leaseInquiry(now.plus(inquiry.lease())));
            return payments.stream().map(Payment::getPaymentId).toList();
        });
        if (due != null) {
            due.forEach(paymentId -> pgCallExecutor.execute(() -> inquire(paymentId)));
        }
    }

    /** 승인 호출 도중 멈춘 REQUESTED는 호출 결과를 모르므로 UNKNOWN으로 옮겨 조회 대상으로 만든다. */
    void recoverStaleRequests() {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = clock.instant();
            repository
                    .claimStaleRequests(now.minus(inquiry.requestedStaleAfter()), inquiry.batchSize())
                    .forEach(payment -> {
                        payment.markUnknown(now, inquiry.initialDelay());
                        log.warn("승인 호출이 끝나지 않은 REQUESTED를 UNKNOWN으로 옮긴다: orderId={}", payment.getOrderId());
                    });
        });
    }

    void inquire(String paymentId) {
        try {
            Payment payment = repository.findById(paymentId).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.UNKNOWN) {
                return;
            }
            InquiryOutcome outcome = pgClient.inquire(payment.getOrderId());
            switch (outcome) {
                case InquiryOutcome.Found found
                when found.isApproved() -> results.approved(paymentId, found.tid(), found.createdAt());
                case InquiryOutcome.Found found
                when found.isDeclined() -> results.failed(paymentId, FailureReason.NOT_APPROVED);
                case InquiryOutcome.Found found -> {
                    log.error("거래 조회 결과가 예상 밖이다: orderId={}, status={}", payment.getOrderId(), found.status());
                    results.inquiryMissed(paymentId);
                }
                case InquiryOutcome.NotFound notFound -> {
                    log.info("PG에 거래 기록이 없다. UNKNOWN을 유지하고 조회를 이어 간다: orderId={}", payment.getOrderId());
                    results.inquiryMissed(paymentId);
                }
                case InquiryOutcome.Failed failed -> results.inquiryMissed(paymentId);
            }
        } catch (RuntimeException e) {
            log.error("거래 조회 처리 중 오류: paymentId={}", paymentId, e);
        }
    }
}
