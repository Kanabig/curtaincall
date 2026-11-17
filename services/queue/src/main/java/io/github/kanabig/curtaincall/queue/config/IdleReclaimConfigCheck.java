package io.github.kanabig.curtaincall.queue.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 회수 시간이 점유 TTL + gateway 활동 기록 간격보다 짧으면 유효한 점유를 가진 사용자가 회수될 수 있다. 두 값은 다른 서비스 설정이라 여기서는 경고만 남기고 기동은 막지 않는다.
 * 비교용 값(hold-ttl, activity-record-interval)은 reservation·gateway 설정을 바꿀 때 함께 맞춘다.
 */
@Component
public class IdleReclaimConfigCheck {

    private static final Logger log = LoggerFactory.getLogger(IdleReclaimConfigCheck.class);

    private final QueueProperties properties;
    private final Duration holdTtl;
    private final Duration recordInterval;

    public IdleReclaimConfigCheck(
            QueueProperties properties,
            @Value("${curtaincall.queue.hold-ttl:5m}") Duration holdTtl,
            @Value("${curtaincall.queue.activity-record-interval:30s}") Duration recordInterval) {
        this.properties = properties;
        this.holdTtl = holdTtl;
        this.recordInterval = recordInterval;
    }

    @EventListener(ApplicationReadyEvent.class)
    void check() {
        Duration idle = properties.idleReclaimAfter();
        if (idle.isZero()) {
            return;
        }
        Duration needed = holdTtl.plus(recordInterval);
        if (idle.compareTo(needed) < 0) {
            log.warn("idle-reclaim-after({})가 점유 TTL + 활동 기록 간격({})보다 짧다. 유효한 점유가 있는 사용자가 회수될 수 있다", idle, needed);
        }
    }
}
