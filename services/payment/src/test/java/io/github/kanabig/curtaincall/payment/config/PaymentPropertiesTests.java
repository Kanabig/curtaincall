package io.github.kanabig.curtaincall.payment.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PaymentPropertiesTests {

    @EnableConfigurationProperties(PaymentProperties.class)
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    @DisplayName("승인 Bulkhead 기본값은 동시 50, maxWait 1초다")
    void defaults() {
        runner.run(context -> {
            PaymentProperties.Pg pg = context.getBean(PaymentProperties.class).pg();
            assertThat(pg.maxConcurrentApprovals()).isEqualTo(50);
            assertThat(pg.approvalMaxWait()).isEqualTo(Duration.ofSeconds(1));
        });
    }

    @Test
    @DisplayName("설정 키 오타는 기동 실패로 드러난다")
    void unknownKeyFailsStartup() {
        runner.withPropertyValues("payment.pg.approval-max-wiat=2s")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.pg.max-concurrent-approval=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("0 이하 동시 수나 음수 maxWait는 기동 실패다")
    void invalidValuesFailStartup() {
        runner.withPropertyValues("payment.pg.max-concurrent-approvals=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("payment.pg.approval-max-wait=-1s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("승인 Bulkhead만 기다리고 조회·취소 Bulkhead는 maxWait 0이다")
    void onlyApprovalBulkheadWaits() {
        runner.withPropertyValues("payment.pg.approval-max-wait=2s").run(context -> {
            PaymentProperties properties = context.getBean(PaymentProperties.class);
            PgClientConfig config = new PgClientConfig();
            BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
            SimpleMeterRegistry meters = new SimpleMeterRegistry();

            assertThat(config.pgApprovalBulkhead(registry, properties, meters)
                            .getBulkheadConfig()
                            .getMaxWaitDuration())
                    .isEqualTo(Duration.ofSeconds(2));
            assertThat(config.pgQueryBulkhead(registry, properties, meters)
                            .getBulkheadConfig()
                            .getMaxWaitDuration())
                    .isEqualTo(Duration.ZERO);
        });
    }
}
