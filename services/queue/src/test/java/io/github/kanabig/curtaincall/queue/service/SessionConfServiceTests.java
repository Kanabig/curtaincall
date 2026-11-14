package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.repository.SessionConf;
import io.github.kanabig.curtaincall.queue.repository.SessionConf.Type;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 회차 이벤트 반영 규칙: 중복 소비 멱등, 순서가 뒤집힌 이벤트 무시, 중지·삭제는 되돌리지 않는다. */
class SessionConfServiceTests extends QueueIntegrationTest {

    @Autowired
    SessionConfService service;

    private Map<Object, Object> conf() {
        return redis.opsForHash().entries("q:" + sessionId + ":conf");
    }

    private boolean open() {
        return Boolean.TRUE.equals(redis.opsForSet().isMember("q:open", String.valueOf(sessionId)));
    }

    private SessionConf upsert(long ver, long openAt, int admission, int maxActive) {
        return new SessionConf(sessionId, Type.UPSERT, ver, openAt, admission, maxActive);
    }

    @Test
    @DisplayName("같은 이벤트를 여러 번 적용해도 conf는 같고 판매 중 회차 집합에 한 번만 들어간다")
    void duplicateIsIdempotent() {
        SessionConf event = upsert(1000, 1_800_000_000_000L, 50, 300);

        service.apply(event);
        Map<Object, Object> once = conf();
        service.apply(event);
        service.apply(event);

        assertThat(conf()).isEqualTo(once);
        assertThat(once)
                .containsEntry("saleOpenAt", "1800000000000")
                .containsEntry("admissionPerSecond", "50")
                .containsEntry("maxActiveUsers", "300")
                .containsEntry("status", "ON_SALE")
                .containsEntry("ver", "1000");
        assertThat(open()).isTrue();
    }

    @Test
    @DisplayName("나중에 온 오래된 SessionUpdated는 무시하고 새 값이 남는다")
    void staleUpdateIgnored() {
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));
        service.apply(upsert(3000, 1_800_000_100_000L, 80, 500));
        service.apply(upsert(2000, 1_800_000_050_000L, 60, 400));

        assertThat(conf())
                .containsEntry("saleOpenAt", "1800000100000")
                .containsEntry("admissionPerSecond", "80")
                .containsEntry("maxActiveUsers", "500")
                .containsEntry("ver", "3000");
    }

    @Test
    @DisplayName("중지하면 판매 중 집합에서 빠지고 이후 SessionUpdated는 무시해 다시 열지 않는다")
    void suspendIsTerminal() {
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));
        service.apply(new SessionConf(sessionId, Type.SUSPEND, 2000, 0, 0, 0));

        assertThat(conf()).containsEntry("status", "SUSPENDED");
        assertThat(open()).isFalse();

        service.apply(upsert(3000, 1_800_000_000_000L, 70, 300));
        assertThat(conf()).containsEntry("status", "SUSPENDED").containsEntry("admissionPerSecond", "50");
        assertThat(open()).isFalse();
    }

    @Test
    @DisplayName("중지 이벤트보다 오래된 SessionCreated가 뒤늦게 와도 중지 상태가 유지된다")
    void lateCreatedAfterSuspend() {
        service.apply(new SessionConf(sessionId, Type.SUSPEND, 2000, 0, 0, 0));
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));

        assertThat(conf()).containsEntry("status", "SUSPENDED");
        assertThat(open()).isFalse();
    }

    @Test
    @DisplayName("SessionUpdated가 먼저 처리돼도 더 오래된 SessionSuspended는 반영하고, 저장된 ver는 큰 값으로 남는다")
    void suspendAppliedEvenIfOlderThanUpdate() {
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));
        service.apply(upsert(101_000, 1_800_000_000_000L, 70, 300));
        service.apply(new SessionConf(sessionId, Type.SUSPEND, 100_000, 0, 0, 0));

        assertThat(conf()).containsEntry("status", "SUSPENDED").containsEntry("ver", "101000");
        assertThat(open()).isFalse();
    }

    @Test
    @DisplayName("SessionUpdated가 먼저 처리돼도 더 오래된 SessionDeleted는 반영해 대기열 키를 지운다")
    void deleteAppliedEvenIfOlderThanUpdate() {
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));
        redis.opsForZSet().add("q:" + sessionId + ":waiting", "1", 1);
        service.apply(upsert(101_000, 1_800_000_000_000L, 70, 300));
        service.apply(new SessionConf(sessionId, Type.DELETE, 100_000, 0, 0, 0));

        assertThat(conf()).containsEntry("status", "DELETED").containsEntry("ver", "101000");
        assertThat(redis.hasKey("q:" + sessionId + ":waiting")).isFalse();
        assertThat(open()).isFalse();
    }

    @Test
    @DisplayName("삭제하면 대기열 키를 지우고 이후 늦게 온 이벤트가 되살리지 못한다")
    void deleteRemovesQueue() {
        service.apply(upsert(1000, 1_800_000_000_000L, 50, 300));
        redis.opsForZSet().add("q:" + sessionId + ":waiting", "1", 1);
        redis.opsForValue().set("q:" + sessionId + ":seq", "1");

        service.apply(new SessionConf(sessionId, Type.DELETE, 2000, 0, 0, 0));
        service.apply(upsert(1500, 1_800_000_000_000L, 50, 300));

        assertThat(conf()).containsEntry("status", "DELETED");
        assertThat(redis.hasKey("q:" + sessionId + ":waiting")).isFalse();
        assertThat(redis.hasKey("q:" + sessionId + ":seq")).isFalse();
        assertThat(open()).isFalse();
    }
}
