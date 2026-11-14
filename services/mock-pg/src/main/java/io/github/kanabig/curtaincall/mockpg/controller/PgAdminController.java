package io.github.kanabig.curtaincall.mockpg.controller;

import io.github.kanabig.curtaincall.mockpg.dto.FaultSettings;
import io.github.kanabig.curtaincall.mockpg.service.FaultService;
import io.github.kanabig.curtaincall.mockpg.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** PAY-2: k6·장애 주입 스크립트가 런타임에 장애 모드를 바꾸는 API. */
@RestController
@RequestMapping("/pg/admin")
public class PgAdminController {

    private final FaultService faultService;
    private final TransactionService transactionService;

    public PgAdminController(FaultService faultService, TransactionService transactionService) {
        this.faultService = faultService;
        this.transactionService = transactionService;
    }

    @GetMapping("/faults")
    public FaultSettings getFaults() {
        return faultService.get();
    }

    @PutMapping("/faults")
    public FaultSettings putFaults(@Valid @RequestBody FaultSettings settings) {
        return faultService.replace(settings);
    }

    /** 모든 장애 모드를 끈다. */
    @DeleteMapping("/faults")
    public FaultSettings resetFaults() {
        return faultService.reset();
    }

    /** 측정 사이에 거래 기록을 비운다. */
    @DeleteMapping("/transactions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearTransactions() {
        transactionService.clear();
    }
}
