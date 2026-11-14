package io.github.kanabig.curtaincall.reservation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 주문·결제 설정. {@code minHoldRemaining}은 결제 시작에 필요한 점유 남은 시간, {@code responseTimeout}은 결제 응답을 기다리다 명령을 다시 보내는 시간,
 * {@code maxResends}는 재전송 횟수 상한, {@code paymentHoldExtension}은 결제 시작(재전송 포함) 때 점유를 늘려 두는 시간이다.
 */
@ConfigurationProperties("curtaincall.order")
public record OrderProperties(
        @DefaultValue("30s") Duration minHoldRemaining,
        @DefaultValue("2m") Duration responseTimeout,
        @DefaultValue("3") int maxResends,
        @DefaultValue("15m") Duration paymentHoldExtension) {}
