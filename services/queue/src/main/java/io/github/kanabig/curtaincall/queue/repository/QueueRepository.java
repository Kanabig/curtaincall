package io.github.kanabig.curtaincall.queue.repository;

import io.github.kanabig.curtaincall.queue.repository.EntryResult.Kind;
import java.util.List;
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

    public QueueRepository(
            StringRedisTemplate redis,
            DefaultRedisScript<List> enterScript,
            DefaultRedisScript<List> statusScript,
            DefaultRedisScript<Long> applyConfScript) {
        this.redis = redis;
        this.enterScript = enterScript;
        this.statusScript = statusScript;
        this.applyConfScript = applyConfScript;
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
