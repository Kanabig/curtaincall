package io.github.kanabig.curtaincall.queue.config;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 대기열 설정.
 *
 * @param zone 응답 시각(입장 만료 시각, 오픈 시각)을 표기할 시간대
 * @param entryTtl 입장 토큰 유효 시간. 활성 목록의 만료 시각도 같은 값으로 정한다(QUE-5)
 * @param staleAfter 대기 중 폴링이 이만큼 없으면 대기열에서 제거한다(QUE-6)
 * @param admitStaleAfter 입장 순서가 된 사람의 마지막 폴링이 이보다 오래됐으면 입장 대신 제거하고 다음 사람을 입장시킨다. 폴링 간격 최대 5초(지터 포함 6초)의 두세 배
 * @param admitDropLimit 한 번의 입장 처리에서 폴링이 끊긴 대기자를 지우는 최대 수. 닿으면 그 틱은 멈춘다
 */
@ConfigurationProperties("curtaincall.queue")
public record QueueProperties(
        @DefaultValue("Asia/Seoul") ZoneId zone,
        @DefaultValue("10m") Duration entryTtl,
        @DefaultValue("60s") Duration staleAfter,
        @DefaultValue("15s") Duration admitStaleAfter,
        @DefaultValue("1000") int admitDropLimit) {}
