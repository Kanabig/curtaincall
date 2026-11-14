package io.github.kanabig.curtaincall.payment.service;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.payment.entity.PaymentStatus;
import io.github.kanabig.curtaincall.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 결제 지표. 서킷 상태와 Bulkhead 지표는 Resilience4j 바인더가, Bulkhead 거절 수는 설정에서 등록한다. */
@Component
public class PaymentMetrics {

    private final Counter unknownExhausted;

    public PaymentMetrics(MeterRegistry meterRegistry, PaymentRepository repository) {
        Gauge.builder(MetricNames.PAYMENT_UNKNOWN, () -> repository.countByStatus(PaymentStatus.UNKNOWN))
                .description("결과를 모른 채 남아 있는 결제 수")
                .register(meterRegistry);
        this.unknownExhausted = Counter.builder(MetricNames.PAYMENT_ATTENTION)
                .tag(MetricNames.TAG_STATUS, "UNKNOWN")
                .description("거래 조회 제한 시간 안에 결과를 얻지 못해 UNKNOWN으로 남은 건수")
                .register(meterRegistry);
    }

    public void unknownExhausted() {
        unknownExhausted.increment();
    }
}
