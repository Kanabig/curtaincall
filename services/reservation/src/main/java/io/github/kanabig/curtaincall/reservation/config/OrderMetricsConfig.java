package io.github.kanabig.curtaincall.reservation.config;

import io.github.kanabig.curtaincall.common.web.metrics.MetricNames;
import io.github.kanabig.curtaincall.reservation.entity.OrderStatus;
import io.github.kanabig.curtaincall.reservation.repository.OrderRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 상태별 주문 수. 종결 상태가 아닌 주문이 쌓이는지 본다. */
@Configuration(proxyBeanMethods = false)
public class OrderMetricsConfig {

    @Bean
    MeterBinder orderCountMetrics(OrderRepository repository) {
        return registry -> {
            for (OrderStatus status : OrderStatus.values()) {
                Gauge.builder(MetricNames.ORDER_COUNT, () -> repository.countByStatus(status))
                        .tag(MetricNames.TAG_STATE, status.name())
                        .description("상태별 주문 수")
                        .register(registry);
            }
        };
    }
}
