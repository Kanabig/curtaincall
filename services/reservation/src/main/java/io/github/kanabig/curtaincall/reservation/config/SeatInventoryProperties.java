package io.github.kanabig.curtaincall.reservation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 재고 생성 설정. 실패한 회차는 {@code retryBackoff}부터 두 배씩 늘려 {@code maxBackoff}까지 간격을 두고 다시 시도한다. {@code lease}는 처리를 맡은 인스턴스가
 * 죽었을 때 다른 인스턴스가 이어받기까지의 시간이라 event 호출 timeout 합보다 길어야 한다.
 */
@ConfigurationProperties("curtaincall.inventory")
public record SeatInventoryProperties(
        @DefaultValue("1000") int insertBatchSize,
        @DefaultValue("2s") Duration retryBackoff,
        @DefaultValue("60s") Duration maxBackoff,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10") int maxPerRun,
        @DefaultValue Scheduler scheduler) {

    public record Scheduler(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration interval) {}
}
