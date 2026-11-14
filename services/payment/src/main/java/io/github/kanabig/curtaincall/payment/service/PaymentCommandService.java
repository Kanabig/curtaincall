package io.github.kanabig.curtaincall.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.kanabig.curtaincall.outbox.message.EventEnvelope;
import io.github.kanabig.curtaincall.outbox.message.EventTypes;
import io.github.kanabig.curtaincall.payment.config.PaymentProperties;
import io.github.kanabig.curtaincall.payment.dto.ApprovePaymentCommand;
import io.github.kanabig.curtaincall.payment.dto.CancelPaymentCommand;
import io.github.kanabig.curtaincall.payment.entity.Payment;
import io.github.kanabig.curtaincall.payment.repository.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * [PAY-1, PAY-3] {@code payment.command}를 처리한다. IdempotentConsumer의 트랜잭션 안에서 호출된다.
 *
 * <p>같은 주문의 ApprovePayment는 payment 행이 하나(order_id UNIQUE)라 PG 승인을 한 번만 시도한다. 이미 있는 주문이면 현재 결과를 다시 응답한다. PG 호출은
 * 트랜잭션 밖에서 하므로 새 결제를 커밋한 뒤에 시작한다.
 */
@Service
@Transactional(readOnly = true)
public class PaymentCommandService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommandService.class);

    private final PaymentRepository repository;
    private final PaymentEvents events;
    private final PaymentApprovalService approvalService;
    private final PaymentCancelService cancelService;
    private final ObjectMapper objectMapper;
    private final PaymentProperties.Inquiry inquiry;
    private final PaymentProperties.Cancel cancel;
    private final Clock clock;

    public PaymentCommandService(
            PaymentRepository repository,
            PaymentEvents events,
            PaymentApprovalService approvalService,
            PaymentCancelService cancelService,
            ObjectMapper objectMapper,
            PaymentProperties properties,
            Clock clock) {
        this.repository = repository;
        this.events = events;
        this.approvalService = approvalService;
        this.cancelService = cancelService;
        this.objectMapper = objectMapper;
        this.inquiry = properties.inquiry();
        this.cancel = properties.cancel();
        this.clock = clock;
    }

    @Transactional
    public void handle(EventEnvelope envelope) {
        String name = EventTypes.nameOf(envelope.eventType());
        if ("ApprovePayment".equals(name)) {
            approve(read(envelope, ApprovePaymentCommand.class));
        } else if ("CancelPayment".equals(name)) {
            cancel(read(envelope, CancelPaymentCommand.class));
        } else {
            log.warn("처리하지 않는 명령이다: eventType={}, eventId={}", envelope.eventType(), envelope.eventId());
        }
    }

    private void approve(ApprovePaymentCommand command) {
        // 잠그지 않고 먼저 읽는다. 없는 행에 FOR UPDATE를 걸면 갭 락이 생겨 서로 다른 주문의 INSERT가 교착한다
        Payment existing = repository.findByOrderId(command.orderId()).orElse(null);
        if (existing != null) {
            respondWithCurrentResult(lock(existing));
            return;
        }
        Payment payment = repository.saveAndFlush(Payment.request(
                command.orderId(), command.userId(), command.amount(), command.cardToken(), clock.instant()));
        String paymentId = payment.getPaymentId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                approvalService.dispatch(paymentId);
            }
        });
    }

    /**
     * [PAY-5] 환불 명령. 이미 CANCELLED면 PG를 다시 부르지 않고 같은 응답을 다시 보낸다. 환불할 승인이 없는 결제(승인 전, 실패)는 응답하지 않는다. 오케스트레이터는 승인
     * 응답을 받은 뒤에만 취소를 보내므로 정상 흐름에서는 오지 않는다.
     */
    private void cancel(CancelPaymentCommand command) {
        Payment payment = repository.findByOrderId(command.orderId()).orElse(null);
        if (payment == null) {
            log.warn("결제가 없는 주문의 취소 명령이다: orderId={}", command.orderId());
            return;
        }
        if (command.paymentId() != null && !command.paymentId().equals(payment.getPaymentId())) {
            log.warn("결제 ID가 맞지 않는 취소 명령이다: orderId={}, paymentId={}", command.orderId(), command.paymentId());
            return;
        }
        payment = lock(payment);
        switch (payment.getStatus()) {
            case CANCELLED -> events.cancelled(payment);
            case CANCELLING -> {
                // 다음 시도 예약은 DB에 남아 프로세스가 죽어도 스케줄러가 이어받는다. 예약이 없는 CANCELLING만 다시 시작한다
                if (payment.getNextCancelAt() == null) {
                    payment.restartCancel(clock.instant(), cancel.lease());
                    dispatchAfterCommit(payment.getPaymentId());
                } else {
                    log.info("취소가 진행 중이라 재전송된 명령은 무시한다: orderId={}", payment.getOrderId());
                }
            }
            case APPROVED, CANCEL_FAILED -> {
                payment.startCancel(command.reason(), clock.instant(), cancel.lease());
                dispatchAfterCommit(payment.getPaymentId());
            }
            case REQUESTED, UNKNOWN, FAILED ->
                log.error(
                        "환불할 승인이 없는 결제의 취소 명령이다. 응답하지 않는다: orderId={}, status={}",
                        payment.getOrderId(),
                        payment.getStatus());
        }
    }

    private void dispatchAfterCommit(String paymentId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cancelService.dispatch(paymentId);
            }
        });
    }

    private Payment lock(Payment payment) {
        return repository
                .findForUpdate(payment.getPaymentId())
                .orElseThrow(() -> new IllegalStateException("결제를 찾을 수 없다: " + payment.getPaymentId()));
    }

    private void respondWithCurrentResult(Payment payment) {
        Instant now = clock.instant();
        switch (payment.getStatus()) {
            case APPROVED -> events.approved(payment);
            case FAILED -> events.failed(payment);
            case UNKNOWN -> {
                if (payment.restartInquiry(now, inquiry.initialDelay())) {
                    log.info("명령이 다시 와서 소진된 거래 조회를 처음부터 다시 한다: orderId={}", payment.getOrderId());
                } else {
                    log.info("거래 조회가 진행 중이라 재전송된 명령은 무시한다: orderId={}", payment.getOrderId());
                }
            }
            case REQUESTED -> {
                // 승인 호출이 진행 중이면 결과가 나올 때 응답한다. 오래 멈춰 있으면 호출 결과를 알 수 없으므로 조회로 확인한다
                if (payment.getCreatedAt().plus(inquiry.requestedStaleAfter()).isBefore(now)) {
                    payment.markUnknown(now, inquiry.initialDelay());
                    log.warn("승인 호출이 끝나지 않은 채 오래됐다. 거래 조회로 확인한다: orderId={}", payment.getOrderId());
                }
            }
        }
    }

    private <T> T read(EventEnvelope envelope, Class<T> type) {
        try {
            return objectMapper.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(type.getSimpleName() + " payload를 읽을 수 없다: " + envelope.eventId(), e);
        }
    }
}
