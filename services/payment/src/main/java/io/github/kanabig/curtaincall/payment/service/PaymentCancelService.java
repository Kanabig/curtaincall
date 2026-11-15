package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.payment.client.CancelOutcome;
import io.github.kanabig.curtaincall.payment.client.InquiryOutcome;
import io.github.kanabig.curtaincall.payment.client.PgClient;
import io.github.kanabig.curtaincall.payment.config.PaymentProperties;
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
 * [PAY-5] PG 취소(환불). 취소는 PG에서 멱등이라 승인과 달리 다시 보내도 환불이 두 번 나가지 않는다. 그래서 실패하면 백오프로 다시 시도한다. 재시도마다 먼저 거래를
 * 조회해 이미 취소됐으면 취소를 다시 보내지 않고 끝낸다(응답 유실로 결과를 모르는 경우). 시도는 {@code next_cancel_at}에 예약되어 프로세스가 죽어도 이어진다.
 */
@Service
public class PaymentCancelService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelService.class);

    private final PaymentRepository repository;
    private final PgClient pgClient;
    private final PaymentResultService results;
    private final ExecutorService pgCallExecutor;
    private final TransactionTemplate transactionTemplate;
    private final PaymentProperties.Cancel cancel;
    private final Clock clock;

    public PaymentCancelService(
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
        this.cancel = properties.cancel();
        this.clock = clock;
    }

    /** 환불을 시작한 트랜잭션이 커밋된 뒤 바로 첫 시도를 한다. */
    public void dispatch(String paymentId) {
        pgCallExecutor.execute(() -> attempt(paymentId));
    }

    /** 한 번 돈다. 시도할 시각이 된 건을 뽑아 임대를 걸고 실행기에 맡긴다. */
    public void runDueCancels() {
        List<String> due = transactionTemplate.execute(status -> {
            Instant now = clock.instant();
            List<Payment> payments = repository.claimDueCancels(now, cancel.batchSize());
            payments.forEach(payment -> payment.leaseCancel(now.plus(cancel.lease())));
            return payments.stream().map(Payment::getPaymentId).toList();
        });
        if (due != null) {
            due.forEach(paymentId -> pgCallExecutor.execute(() -> attempt(paymentId)));
        }
    }

    void attempt(String paymentId) {
        try {
            Payment payment = repository.findById(paymentId).orElse(null);
            if (payment == null || payment.getStatus() != PaymentStatus.CANCELLING) {
                return;
            }
            if (payment.getCancelAttempts() > 0 && alreadyCancelled(payment)) {
                results.cancelled(paymentId);
                return;
            }
            CancelOutcome outcome = pgClient.cancel(payment.getPgTid());
            switch (outcome) {
                case CancelOutcome.Cancelled cancelled -> results.cancelled(paymentId);
                case CancelOutcome.Failed failed -> {
                    log.warn("PG 취소를 확인하지 못했다: orderId={}, detail={}", payment.getOrderId(), failed.detail());
                    results.cancelMissed(paymentId);
                }
            }
        } catch (RuntimeException e) {
            // 결과를 저장하지 못했으면 임대가 끝난 뒤 스케줄러가 다시 시도한다
            log.error("취소 처리 중 오류: paymentId={}", paymentId, e);
        }
    }

    private boolean alreadyCancelled(Payment payment) {
        return pgClient.inquire(payment.getOrderId()) instanceof InquiryOutcome.Found found && found.isCancelled();
    }
}
