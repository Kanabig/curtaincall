package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.payment.client.ApprovalOutcome;
import io.github.kanabig.curtaincall.payment.client.PgClient;
import io.github.kanabig.curtaincall.payment.entity.FailureReason;
import io.github.kanabig.curtaincall.payment.entity.Payment;
import io.github.kanabig.curtaincall.payment.entity.PaymentStatus;
import io.github.kanabig.curtaincall.payment.repository.PaymentRepository;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * [PAY-1, PAY-4] PG 승인 호출. 새 결제를 만든 트랜잭션이 커밋된 뒤 한 번만 시작되고, 재시도하지 않는다. 호출은 전용 실행기에서 하므로 PG가 느려도 컨슈머
 * 스레드는 바로 돌아온다.
 */
@Service
public class PaymentApprovalService {

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovalService.class);

    private final PaymentRepository repository;
    private final PgClient pgClient;
    private final PaymentResultService results;
    private final ExecutorService pgCallExecutor;

    public PaymentApprovalService(
            PaymentRepository repository,
            PgClient pgClient,
            PaymentResultService results,
            ExecutorService pgCallExecutor) {
        this.repository = repository;
        this.pgClient = pgClient;
        this.results = results;
        this.pgCallExecutor = pgCallExecutor;
    }

    public void dispatch(String paymentId) {
        pgCallExecutor.execute(() -> approve(paymentId));
    }

    void approve(String paymentId) {
        Payment payment = repository.findById(paymentId).orElse(null);
        if (payment == null || payment.getStatus() != PaymentStatus.REQUESTED) {
            return;
        }
        try {
            ApprovalOutcome outcome =
                    pgClient.approve(payment.getOrderId(), payment.getAmount(), payment.getCardToken());
            switch (outcome) {
                case ApprovalOutcome.Approved approved ->
                    results.approved(paymentId, approved.tid(), approved.approvedAt());
                case ApprovalOutcome.Declined declined -> {
                    log.info("PG가 승인을 거절했다: orderId={}, code={}", payment.getOrderId(), declined.failureCode());
                    results.failed(paymentId, FailureReason.DECLINED);
                }
                case ApprovalOutcome.Rejected rejected -> {
                    log.warn("PG가 승인 요청을 거절했다: orderId={}, status={}", payment.getOrderId(), rejected.httpStatus());
                    results.failed(paymentId, FailureReason.PG_REJECTED);
                }
                case ApprovalOutcome.NotSent notSent -> {
                    log.warn("PG에 승인을 보내지 않았다: orderId={}, reason={}", payment.getOrderId(), notSent.reason());
                    results.failed(paymentId, FailureReason.PG_UNAVAILABLE);
                }
                case ApprovalOutcome.Unknown unknown -> results.unknown(paymentId);
            }
        } catch (RuntimeException e) {
            // 호출을 보냈는지 알 수 없는 오류. 실패로 단정하지 않는다. 결과 저장도 실패했으면 오래된 REQUESTED로 남아 조회 대상이 된다
            log.error("승인 처리 중 예기치 않은 오류: paymentId={}", paymentId, e);
            markUnknownQuietly(paymentId);
        }
    }

    private void markUnknownQuietly(String paymentId) {
        try {
            results.unknown(paymentId);
        } catch (RuntimeException e) {
            log.error("UNKNOWN 표시에도 실패했다. 오래된 REQUESTED로 남아 조회 대상이 된다: paymentId={}", paymentId, e);
        }
    }
}
