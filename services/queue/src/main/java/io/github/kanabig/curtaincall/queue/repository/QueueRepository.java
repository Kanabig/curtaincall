package io.github.kanabig.curtaincall.queue.repository;

import io.github.kanabig.curtaincall.queue.repository.EntryResult.Kind;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/** 대기열 Redis 접근. 여러 키를 건드리는 동작은 모두 Lua 하나로 원자 처리한다. */
@Repository
public class QueueRepository {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> enterScript;
    private final DefaultRedisScript<List> statusScript;
    private final DefaultRedisScript<Long> applyConfScript;
    private final DefaultRedisScript<List> admitScript;
    private final DefaultRedisScript<Long> removeStaleScript;

    public QueueRepository(
            StringRedisTemplate redis,
            DefaultRedisScript<List> enterScript,
            DefaultRedisScript<List> statusScript,
            DefaultRedisScript<Long> applyConfScript,
            DefaultRedisScript<List> admitScript,
            DefaultRedisScript<Long> removeStaleScript) {
        this.redis = redis;
        this.enterScript = enterScript;
        this.statusScript = statusScript;
        this.applyConfScript = applyConfScript;
        this.admitScript = admitScript;
        this.removeStaleScript = removeStaleScript;
    }

    /** 판매 중인 회차 ID. */
    public Set<Long> openSessions() {
        Set<String> ids = redis.opsForSet().members(QueueKeys.OPEN);
        if (ids == null) {
            return Set.of();
        }
        return ids.stream().map(Long::valueOf).collect(Collectors.toSet());
    }

    /**
     * 회차 하나의 입장 처리. 같은 초에는 인스턴스가 몇 개든 한 번만 실행된다. {@code nowMs}가 null이면 Redis TIME을 쓴다(운영). 값을 주는 것은 시계를 주입하는
     * 테스트용이다.
     */
    public AdmitResult admit(long sessionId, Long nowMs, long entryTtlMs) {
        List<?> r = redis.execute(
                admitScript,
                List.of(
                        QueueKeys.conf(sessionId),
                        QueueKeys.waiting(sessionId),
                        QueueKeys.seen(sessionId),
                        QueueKeys.active(sessionId)),
                nowMs == null ? "" : String.valueOf(nowMs),
                String.valueOf(entryTtlMs),
                QueueKeys.tickPrefix(sessionId));
        if (r == null || r.isEmpty() || ((Number) r.get(0)).longValue() != 1) {
            return AdmitResult.SKIPPED;
        }
        return new AdmitResult(true, ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue());
    }

    /** 마지막 폴링이 {@code staleAfterMs}보다 오래된 대기자를 지운다. 한 번에 {@code limit}명까지. {@code nowMs}가 null이면 Redis TIME. */
    public long removeStale(long sessionId, Long nowMs, long staleAfterMs, int limit) {
        Long removed = redis.execute(
                removeStaleScript,
                List.of(QueueKeys.seen(sessionId), QueueKeys.waiting(sessionId)),
                String.valueOf(limit),
                String.valueOf(staleAfterMs),
                nowMs == null ? "" : String.valueOf(nowMs));
        return removed == null ? 0 : removed;
    }

    /** 대기 포기. 없으면 아무 일도 하지 않는다. */
    public void leave(long sessionId, long userId) {
        String user = String.valueOf(userId);
        redis.opsForZSet().remove(QueueKeys.waiting(sessionId), user);
        redis.opsForZSet().remove(QueueKeys.seen(sessionId), user);
    }

    /** 예매를 마친 사용자를 활성 목록에서 뺀다. 토큰 자체는 만료까지 유효하다. */
    public void removeActive(long sessionId, long userId) {
        redis.opsForZSet().remove(QueueKeys.active(sessionId), String.valueOf(userId));
    }

    public EntryResult enter(long sessionId, long userId) {
        List<?> r = redis.execute(
                enterScript,
                List.of(
                        QueueKeys.conf(sessionId),
                        QueueKeys.waiting(sessionId),
                        QueueKeys.seen(sessionId),
                        QueueKeys.seq(sessionId),
                        QueueKeys.active(sessionId)),
                String.valueOf(userId));
        return parse(r);
    }

    public EntryResult status(long sessionId, long userId) {
        List<?> r = redis.execute(
                statusScript,
                List.of(
                        QueueKeys.conf(sessionId),
                        QueueKeys.waiting(sessionId),
                        QueueKeys.seen(sessionId),
                        QueueKeys.active(sessionId)),
                String.valueOf(userId));
        return parse(r);
    }

    /** @return 반영했으면 true, 이미 반영한 것보다 오래된 이벤트라 버렸으면 false */
    public boolean applyConf(SessionConf conf) {
        long sid = conf.sessionId();
        Long applied = redis.execute(
                applyConfScript,
                List.of(
                        QueueKeys.conf(sid),
                        QueueKeys.OPEN,
                        QueueKeys.waiting(sid),
                        QueueKeys.seen(sid),
                        QueueKeys.seq(sid),
                        QueueKeys.active(sid)),
                String.valueOf(sid),
                conf.type().name(),
                String.valueOf(conf.version()),
                String.valueOf(conf.saleOpenAtMs()),
                String.valueOf(conf.admissionPerSecond()),
                String.valueOf(conf.maxActiveUsers()));
        return applied != null && applied == 1L;
    }

    private static EntryResult parse(List<?> r) {
        if (r == null || r.isEmpty()) {
            throw new IllegalStateException("대기열 스크립트 응답이 비어 있다");
        }
        long code = ((Number) r.get(0)).longValue();
        if (code == 1) {
            return new EntryResult(
                    Kind.WAITING,
                    ((Number) r.get(1)).longValue(),
                    ((Number) r.get(2)).longValue(),
                    0,
                    r.size() > 3 && ((Number) r.get(3)).longValue() == 1);
        }
        if (code == 3) {
            return new EntryResult(Kind.ADMITTED, 0, 0, ((Number) r.get(1)).longValue(), false);
        }
        if (code == -2) {
            return new EntryResult(Kind.NOT_OPENED, 0, 0, ((Number) r.get(1)).longValue(), false);
        }
        if (code == 4) {
            return EntryResult.of(Kind.NOT_IN_QUEUE);
        }
        if (code == -1) {
            return EntryResult.of(Kind.SESSION_NOT_FOUND);
        }
        if (code == -3) {
            return EntryResult.of(Kind.NOT_ON_SALE);
        }
        throw new IllegalStateException("알 수 없는 스크립트 응답: " + code);
    }
}
