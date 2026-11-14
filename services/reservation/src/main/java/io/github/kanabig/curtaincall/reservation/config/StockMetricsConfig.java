package io.github.kanabig.curtaincall.reservation.config;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.reservation.repository.SeatInventoryRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 재고 생성이 밀리는 것을 보기 위한 지표. PENDING 회차가 쌓이면 점유가 SESSION_NOT_ON_SALE로 거절된다. */
@Configuration(proxyBeanMethods = false)
public class StockMetricsConfig {

    @Bean
    MeterBinder pendingStockMetrics(SeatInventoryRepository repository) {
        return registry -> {
            Gauge.builder(MetricNames.SEAT_STOCK_PENDING, repository, SeatInventoryRepository::countPending)
                    .description("재고 생성을 기다리는(PENDING) 회차 수")
                    .register(registry);
            Gauge.builder(
                            MetricNames.SEAT_STOCK_PENDING_OLDEST_AGE,
                            repository,
                            SeatInventoryRepository::oldestPendingAgeSeconds)
                    .baseUnit("seconds")
                    .description("가장 오래된 PENDING 회차의 대기 시간")
                    .register(registry);
        };
    }
}
