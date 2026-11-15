package io.github.kanabig.curtaincall.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * [GW-2] 요청 속도 제한 설정. IP 한도는 같은 공유기·프록시 뒤의 여러 사용자가 함께 쓰므로 계정 한도보다 넉넉하게 둔다.
 *
 * @param ip IP별 토큰 버킷
 * @param user 계정별 토큰 버킷
 * @param timeout Redis 호출 제한 시간. 넘으면 통과시킨다
 * @param circuitOpen Redis 호출이 실패한 뒤 이 시간 동안은 호출하지 않고 통과시킨다. 장애 중 요청마다 타임아웃을 기다리지 않게 한다
 * @param trustForwardedFor true면 X-Forwarded-For의 마지막 값을 클라이언트 IP로 쓴다. gateway 앞에 신뢰하는 프록시가 있을 때만 켠다
 */
@ConfigurationProperties("curtaincall.rate-limit")
public record RateLimitProperties(
        @DefaultValue Bucket ip,
        @DefaultValue Bucket user,
        @DefaultValue("100ms") Duration timeout,
        @DefaultValue("5s") Duration circuitOpen,
        @DefaultValue("false") boolean trustForwardedFor) {

    /**
     * @param ratePerSecond 초당 충전 토큰 수
     * @param burst 버킷 용량(순간 허용량)
     */
    public record Bucket(
            @DefaultValue("10") double ratePerSecond,
            @DefaultValue("20") int burst) {}
}
