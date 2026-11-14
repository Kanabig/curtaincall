package io.github.kanabig.curtaincall.mockpg.service;

import io.github.kanabig.curtaincall.mockpg.dto.ApprovalRequest;
import io.github.kanabig.curtaincall.mockpg.dto.ApprovalResponse;
import io.github.kanabig.curtaincall.mockpg.dto.TransactionResponse;
import io.github.kanabig.curtaincall.mockpg.entity.Transaction;
import io.github.kanabig.curtaincall.mockpg.repository.TransactionRepository;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionService {

    static final String TOKEN_APPROVE = "tok_approve";
    static final String TOKEN_DECLINE = "tok_decline";
    static final String TOKEN_INSUFFICIENT_FUNDS = "tok_insufficient_funds";

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository repository;
    private final FaultService faultService;
    private final Clock clock;

    public TransactionService(TransactionRepository repository, FaultService faultService, Clock clock) {
        this.repository = repository;
        this.faultService = faultService;
        this.clock = clock;
    }

    /** 같은 merchantOrderId는 한 번만 처리하고 이후 요청에는 첫 거래를 돌려준다. */
    public ApprovalResult approve(ApprovalRequest request) {
        faultService.beforeApproval();

        Transaction candidate = newTransaction(request);
        Transaction existing;
        try {
            existing = repository.insertIfAbsent(candidate).orElse(null);
        } catch (TransactionRepository.OrderPreCancelledException e) {
            // 망취소가 먼저 기록된 주문이다. 늦게 도착한 승인은 무효다
            log.info("사전 취소된 주문의 승인을 거절한다: merchantOrderId={}", request.merchantOrderId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "order was cancelled", e);
        }
        Transaction result = existing != null ? existing : candidate;
        boolean duplicate = existing != null;
        log.info(
                "승인 처리: merchantOrderId={}, status={}, duplicate={}",
                request.merchantOrderId(),
                result.getStatus(),
                duplicate);

        return new ApprovalResult(ApprovalResponse.of(result, duplicate), faultService.shouldDropResponse());
    }

    public TransactionResponse findByMerchantOrderId(String merchantOrderId) {
        return repository
                .findByMerchantOrderId(merchantOrderId)
                .map(TransactionResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction not found"));
    }

    /** 이미 취소된 거래는 그대로 성공으로 돌려준다. 승인되지 않은 거래는 취소할 수 없다. */
    public TransactionResponse cancel(String tid) {
        Transaction transaction = repository
                .findByTid(tid)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "transaction not found"));
        if (!transaction.cancel(clock.instant())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "transaction is not approved");
        }
        return TransactionResponse.from(transaction);
    }

    /**
     * 가맹점 주문번호로 취소한다(망취소). 거래가 있으면 tid 취소와 같고, 없으면 사전 취소로 기록한 뒤 그 뒤에 들어오는 같은 주문번호의 승인을 거절한다. 반복 호출해도
     * 같은 결과다.
     */
    public TransactionResponse cancelByMerchantOrderId(String merchantOrderId) {
        Transaction existing = repository.preCancelIfAbsent(merchantOrderId).orElse(null);
        if (existing == null) {
            return TransactionResponse.preCancelled(merchantOrderId, clock.instant());
        }
        return cancel(existing.getTid());
    }

    public void clear() {
        repository.clear();
    }

    private Transaction newTransaction(ApprovalRequest request) {
        String tid = "mpg_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        return switch (request.cardToken()) {
            case TOKEN_APPROVE ->
                Transaction.approved(tid, request.merchantOrderId(), request.amount(), clock.instant());
            case TOKEN_DECLINE ->
                Transaction.declined(
                        tid, request.merchantOrderId(), request.amount(), "CARD_DECLINED", clock.instant());
            case TOKEN_INSUFFICIENT_FUNDS ->
                Transaction.declined(
                        tid, request.merchantOrderId(), request.amount(), "INSUFFICIENT_FUNDS", clock.instant());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown card token");
        };
    }
}
