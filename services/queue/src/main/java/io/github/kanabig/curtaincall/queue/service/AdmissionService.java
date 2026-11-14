package io.github.kanabig.curtaincall.queue.service;

import io.github.kanabig.curtaincall.queue.config.QueueProperties;
import io.github.kanabig.curtaincall.queue.repository.AdmitResult;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * [QUE-4, QUE-6] 입장 처리와 이탈 정리. 운영은 시각을 Redis TIME으로 정하고, 테스트만 시각을 넘긴다. 만료된 활성 사용자는 입장 처리 스크립트 안에서 활성 수를 세기 직전에 지운다.
 */
@Service
public class AdmissionService {

    private static final Logger log = LoggerFactory.getLogger(AdmissionService.class);
    private static final int STALE_BATCH = 1000;

    private final QueueRepository repository;
    private final QueueProperties properties;

    public AdmissionService(QueueRepository repository, QueueProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** 운영 경로. 시각은 Redis TIME이다. */
    public long admitAll() {
        return admitAll(null);
    }

    /** 테스트용. 시각을 주입한다. */
    public long admitAll(Instant now) {
        Long nowMs = now == null ? null : now.toEpochMilli();
        long total = 0;
        for (long sessionId : repository.openSessions()) {
            AdmitResult result = repository.admit(
                    sessionId,
                    nowMs,
                    properties.entryTtl().toMillis(),
                    properties.admitStaleAfter().toMillis());
            if (result.ran() && (result.admitted() > 0 || result.dropped() > 0)) {
                log.debug(
                        "회차 {} 입장 {}명, 폴링 끊김 {}명 제거, 활성 {}명",
                        sessionId,
                        result.admitted(),
                        result.dropped(),
                        result.active());
            }
            total += result.admitted();
        }
        return total;
    }

    /** 운영 경로. 폴링이 {@code staleAfter} 넘게 없는 대기자를 지운다. 시각은 Redis TIME이다. */
    public long removeStale() {
        return removeStale(null);
    }

    /** 테스트용. 시각을 주입한다. */
    public long removeStale(Instant now) {
        Long nowMs = now == null ? null : now.toEpochMilli();
        long total = 0;
        for (long sessionId : repository.openSessions()) {
            long removed;
            do {
                removed = repository.removeStale(
                        sessionId, nowMs, properties.staleAfter().toMillis(), STALE_BATCH);
                total += removed;
            } while (removed == STALE_BATCH);
        }
        return total;
    }
}
