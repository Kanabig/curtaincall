package io.github.kanabig.curtaincall.payment.scheduler;

import io.github.kanabig.curtaincall.payment.service.PaymentInquiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** [PAY-4] 조회 시각이 된 UNKNOWN 결제를 주기적으로 확인한다. */
@Component
public class PaymentInquiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentInquiryScheduler.class);

    private final PaymentInquiryService inquiryService;

    public PaymentInquiryScheduler(PaymentInquiryService inquiryService) {
        this.inquiryService = inquiryService;
    }

    @Scheduled(fixedDelayString = "${payment.inquiry.poll-interval:1s}")
    void run() {
        try {
            inquiryService.runDueInquiries();
        } catch (RuntimeException e) {
            log.error("거래 조회 스케줄 실행에 실패했다", e);
        }
    }
}
