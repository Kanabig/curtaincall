package io.github.kanabig.curtaincall.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * [QUE-5] 입장자의 활동 기록과 활성 소속 확인 설정. 회수 시간은 queue가 정하므로 recordInterval은 그보다 충분히 짧아야 한다.
 *
 * @param recordInterval 사용자당 마지막 활동 시각을 Redis에 쓰는 최소 간격
 * @param timeout Redis 호출 제한 시간. 넘으면 토큰 서명만으로 통과시킨다
 * @param circuitOpen Redis 호출이 실패한 뒤 이 시간 동안은 호출하지 않고 통과시킨다
 */
@ConfigurationProperties("curtaincall.entry-activity")
public record EntryActivityProperties(
        @DefaultValue("30s") Duration recordInterval,
        @DefaultValue("100ms") Duration timeout,
        @DefaultValue("5s") Duration circuitOpen) {}
