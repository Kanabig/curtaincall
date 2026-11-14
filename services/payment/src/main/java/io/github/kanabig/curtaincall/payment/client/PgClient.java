package io.github.kanabig.curtaincall.payment.client;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * [PAY-1, PAY-4] 가짜 PG 호출. 승인에는 재시도를 붙이지 않고(이중 승인 위험), 서킷 브레이커와 Bulkhead를 건다. 거래 조회는 Bulkhead만 거친다.
 *
 * <p>Bulkhead가 바깥, 서킷이 안쪽이다. Bulkhead가 거절한 호출은 서킷의 실패율에 들어가지 않는다.
 */
@Component
public class PgClient {

    private static final Logger log = LoggerFactory.getLogger(PgClient.class);

    private final RestClient restClient;
    private final Bulkhead approvalBulkhead;
    private final Bulkhead queryBulkhead;
    private final CircuitBreaker approvalBreaker;

    public PgClient(
            RestClient pgRestClient,
            Bulkhead pgApprovalBulkhead,
            Bulkhead pgQueryBulkhead,
            CircuitBreaker pgApprovalBreaker) {
        this.restClient = pgRestClient;
        this.approvalBulkhead = pgApprovalBulkhead;
        this.queryBulkhead = pgQueryBulkhead;
        this.approvalBreaker = pgApprovalBreaker;
    }

    public ApprovalOutcome approve(String merchantOrderId, long amount, String cardToken) {
        try {
            PgApproval response = Bulkhead.decorateSupplier(
                            approvalBulkhead,
                            CircuitBreaker.decorateSupplier(
                                    approvalBreaker, () -> requestApproval(merchantOrderId, amount, cardToken)))
                    .get();
            return toOutcome(response);
        } catch (BulkheadFullException e) {
            return new ApprovalOutcome.NotSent(ApprovalOutcome.Reason.BULKHEAD_FULL);
        } catch (CallNotPermittedException e) {
            return new ApprovalOutcome.NotSent(ApprovalOutcome.Reason.CIRCUIT_OPEN);
        } catch (HttpClientErrorException e) {
            // 승인 요청에 대한 4xx는 PG가 요청을 처리하지 않았다는 뜻이다
            log.warn("PG가 승인 요청을 거절했다: merchantOrderId={}, status={}", merchantOrderId, e.getStatusCode());
            return new ApprovalOutcome.Rejected(e.getStatusCode().value());
        } catch (RuntimeException e) {
            // 타임아웃, premature EOF, 5xx 모두 PG가 처리했는지 알 수 없다
            log.warn("PG 승인 결과를 받지 못했다: merchantOrderId={}, cause={}", merchantOrderId, e.toString());
            return new ApprovalOutcome.Unknown(e.getClass().getSimpleName());
        }
    }

    public InquiryOutcome inquire(String merchantOrderId) {
        try {
            PgTransaction transaction = Bulkhead.decorateSupplier(
                            queryBulkhead, () -> requestTransaction(merchantOrderId))
                    .get();
            if (transaction == null) {
                return new InquiryOutcome.Failed("empty body");
            }
            return new InquiryOutcome.Found(
                    transaction.tid(), transaction.status(), transaction.failureCode(), transaction.createdAt());
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return new InquiryOutcome.NotFound();
            }
            return new InquiryOutcome.Failed(e.getStatusCode().toString());
        } catch (BulkheadFullException e) {
            return new InquiryOutcome.Failed("bulkhead full");
        } catch (RuntimeException e) {
            log.warn("PG 거래 조회에 실패했다: merchantOrderId={}, cause={}", merchantOrderId, e.toString());
            return new InquiryOutcome.Failed(e.getClass().getSimpleName());
        }
    }

    private PgApproval requestApproval(String merchantOrderId, long amount, String cardToken) {
        PgApproval response = restClient
                .post()
                .uri("/pg/v1/approvals")
                .body(new PgApprovalRequest(merchantOrderId, amount, cardToken))
                .retrieve()
                .body(PgApproval.class);
        if (response == null) {
            throw new IllegalStateException("PG 승인 응답 본문이 비어 있다");
        }
        return response;
    }

    private PgTransaction requestTransaction(String merchantOrderId) {
        return restClient
                .get()
                .uri(uri -> uri.path("/pg/v1/transactions")
                        .queryParam("merchantOrderId", merchantOrderId)
                        .build())
                .retrieve()
                .body(PgTransaction.class);
    }

    private ApprovalOutcome toOutcome(PgApproval response) {
        return switch (response.status()) {
            case "APPROVED" -> new ApprovalOutcome.Approved(response.tid(), response.createdAt());
            case "DECLINED" -> new ApprovalOutcome.Declined(response.failureCode());
            default -> new ApprovalOutcome.Unknown("status=" + response.status());
        };
    }

    record PgApprovalRequest(String merchantOrderId, long amount, String cardToken) {}

    record PgApproval(
            String tid,
            String merchantOrderId,
            String status,
            long amount,
            String failureCode,
            Instant createdAt,
            boolean duplicate) {}

    record PgTransaction(
            String tid,
            String merchantOrderId,
            String status,
            long amount,
            String failureCode,
            Instant createdAt,
            Instant cancelledAt) {}
}
