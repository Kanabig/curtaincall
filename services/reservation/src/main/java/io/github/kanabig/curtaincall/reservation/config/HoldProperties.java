package io.github.kanabig.curtaincall.reservation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 좌석 점유 설정. {@code purchaseLimit}은 회차당 1인 매수 한도(점유 + 확정)다. */
@ConfigurationProperties("curtaincall.hold")
public record HoldProperties(
        @DefaultValue("5m") Duration ttl,
        @DefaultValue("4") int purchaseLimit,
        @DefaultValue("conditional-update") String strategy) {}
