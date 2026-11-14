package io.github.kanabig.curtaincall.payment.scheduler;

import io.github.kanabig.curtaincall.payment.service.PaymentCancelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** [PAY-5] 시도 시각이 된 CANCELLING 결제의 PG 취소를 주기적으로 이어 간다. */
@Component
public class PaymentCancelScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentCancelScheduler.class);

    private final PaymentCancelService cancelService;

    public PaymentCancelScheduler(PaymentCancelService cancelService) {
        this.cancelService = cancelService;
    }

    @Scheduled(fixedDelayString = "${payment.cancel.poll-interval:1s}")
    void run() {
        try {
            cancelService.runDueCancels();
        } catch (RuntimeException e) {
            log.error("취소 스케줄 실행에 실패했다", e);
        }
    }
}
