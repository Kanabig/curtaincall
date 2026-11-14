package io.github.kanabig.curtaincall.mockpg.controller;

import io.github.kanabig.curtaincall.mockpg.dto.ApprovalRequest;
import io.github.kanabig.curtaincall.mockpg.dto.ApprovalResponse;
import io.github.kanabig.curtaincall.mockpg.dto.TransactionResponse;
import io.github.kanabig.curtaincall.mockpg.service.ApprovalResult;
import io.github.kanabig.curtaincall.mockpg.service.TransactionService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** PAY-1, PAY-4, PAY-5가 호출하는 PG 인터페이스(spec/api.md 가짜 PG). */
@RestController
@RequestMapping("/pg/v1")
public class PgController {

    private final TransactionService transactionService;

    public PgController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/approvals")
    public ResponseEntity<ApprovalResponse> approve(
            @Valid @RequestBody ApprovalRequest request, HttpServletResponse servletResponse) throws IOException {
        ApprovalResult result = transactionService.approve(request);
        if (result.dropResponse()) {
            ConnectionDrop.drop(servletResponse);
            return null;
        }
        return ResponseEntity.ok(result.response());
    }

    /** 거래가 없으면 404. PG가 해당 주문을 받은 적이 없다는 뜻이다. */
    @GetMapping("/transactions")
    public TransactionResponse find(@RequestParam String merchantOrderId) {
        return transactionService.findByMerchantOrderId(merchantOrderId);
    }

    /** 망취소. 거래가 없는 주문번호도 취소로 기록하고 성공을 돌려준다. */
    @PostMapping("/transactions/cancel")
    public TransactionResponse cancelByMerchantOrderId(@RequestParam String merchantOrderId) {
        return transactionService.cancelByMerchantOrderId(merchantOrderId);
    }

    @PostMapping("/transactions/{tid}/cancel")
    public TransactionResponse cancel(@PathVariable String tid) {
        return transactionService.cancel(tid);
    }
}
