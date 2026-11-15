package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.kanabig.curtaincall.common.web.error.BusinessException;
import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.dto.QueueStatusResponse;
import io.github.kanabig.curtaincall.queue.repository.SessionConf;
import io.github.kanabig.curtaincall.queue.repository.SessionConf.Type;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** [QUE-8] 매진 중에는 입장을 멈추고 대기자에게 SOLD_OUT을 주며, 매진이 풀리면 원래 순서로 재개한다. */
class SoldOutTests extends QueueIntegrationTest {

    @Autowired
    AdmissionService admission;

    @Autowired
    QueueService queue;

    @Autowired
    SessionConfService confService;

    private String key(String name) {
        return "q:" + sessionId + ":" + name;
    }

    private boolean isActive(long userId) {
        return redis.opsForZSet().score(key("active"), String.valueOf(userId)) != null;
    }

    private long activeCount() {
        Long n = redis.opsForZSet().zCard(key("active"));
        return n == null ? 0 : n;
    }

    private void enterUsers(int from, int to) {
        for (int u = from; u <= to; u++) {
            queue.enter(sessionId, u);
        }
    }

    private void pollUntil(int from, int to, Instant at) {
        for (int u = from; u <= to; u++) {
            redis.opsForZSet().add(key("seen"), String.valueOf(u), at.toEpochMilli());
        }
    }

    private static Instant startOfSecond() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @DisplayName("[QUE-8] 매진 중에는 입장이 0이고 대기자 순번은 그대로이며, 입장한 사람은 영향이 없다")
    void soldOutStopsAdmissionAndKeepsOrder() {
        putConf(-1000, 3);
        enterUsers(1, 8);
        Instant t0 = startOfSecond();
        assertThat(admission.admitAll(t0)).isEqualTo(3);
        pollUntil(4, 8, t0.plusSeconds(10));

        confService.applyAvailability(sessionId, true, 1);

        assertThat(admission.admitAll(t0.plusSeconds(1))).isZero();
        assertThat(admission.admitAll(t0.plusSeconds(2))).isZero();
        assertThat(activeCount()).isEqualTo(3);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(5);
        assertThat(queue.status(sessionId, 1).status()).isEqualTo("ADMITTED");
        QueueStatusResponse waiting = queue.status(sessionId, 6);
        assertThat(waiting.status()).isEqualTo("SOLD_OUT");
        assertThat(waiting.position()).isEqualTo(3);
        assertThat(waiting.ahead()).isEqualTo(2);
        assertThat(waiting.etaSeconds()).isNull();
        assertThat(waiting.pollAfterMs()).isBetween(4000L, 6000L);
    }

    @Test
    @DisplayName("[QUE-8] 매진 중 새로 들어와도 같은 SOLD_OUT 응답과 마지막 순번을 받는다")
    void newEntryWhileSoldOut() {
        putConf(-1000, 3);
        enterUsers(1, 4);
        confService.applyAvailability(sessionId, true, 1);

        QueueService.Entered entered = queue.enter(sessionId, 99);

        assertThat(entered.created()).isTrue();
        assertThat(entered.response().status()).isEqualTo("SOLD_OUT");
        assertThat(entered.response().position()).isEqualTo(5);
        QueueService.Entered again = queue.enter(sessionId, 99);
        assertThat(again.created()).isFalse();
        assertThat(again.response().position()).isEqualTo(5);
    }

    @Test
    @DisplayName("[QUE-8] 매진 중에도 활성 목록의 만료 정리는 계속된다")
    void expiredActiveIsStillCleaned() {
        putConf(-1000, 3);
        enterUsers(1, 3);
        Instant t0 = startOfSecond();
        admission.admitAll(t0);
        assertThat(activeCount()).isEqualTo(3);
        confService.applyAvailability(sessionId, true, 1);

        // 입장 TTL(10분)이 지난 시각으로 돌린다
        admission.admitAll(t0.plus(11, ChronoUnit.MINUTES));

        assertThat(activeCount()).isZero();
    }

    @Test
    @DisplayName("[QUE-8] 매진이 풀리면 대기열에 남아 있던 앞 순번부터 입장을 재개한다")
    void resumesInOriginalOrder() {
        putConf(-1000, 3);
        enterUsers(1, 9);
        Instant t0 = startOfSecond();
        admission.admitAll(t0);
        pollUntil(4, 9, t0.plusSeconds(10));
        confService.applyAvailability(sessionId, true, 1);
        assertThat(admission.admitAll(t0.plusSeconds(1))).isZero();

        confService.applyAvailability(sessionId, false, 2);

        assertThat(admission.admitAll(t0.plusSeconds(2))).isEqualTo(3);
        assertThat(isActive(4) && isActive(5) && isActive(6)).isTrue();
        assertThat(isActive(7)).isFalse();
        assertThat(queue.status(sessionId, 7).status()).isEqualTo("WAITING");
    }

    @Test
    @DisplayName("[QUE-8] 중복·역순으로 도착해도 마지막 version의 상태로 수렴한다")
    void convergesToLatestVersionInAnyOrder() {
        long[][] events = {{1, 1}, {2, 0}, {3, 1}}; // {version, soldOut}
        int[][] orders = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
        for (int[] order : orders) {
            redis.delete(key("conf"));
            for (int index : order) {
                confService.applyAvailability(sessionId, events[index][1] == 1, events[index][0]);
                // 중복 전달
                confService.applyAvailability(sessionId, events[index][1] == 1, events[index][0]);
            }
            assertThat(redis.opsForHash().get(key("conf"), "soldOut"))
                    .as("order %s", List.of(order))
                    .isEqualTo("1");
            assertThat(redis.opsForHash().get(key("conf"), "soldOutVer")).isEqualTo("3");
        }
    }

    @Test
    @DisplayName("[QUE-8] 같은 version을 다시 보내거나 더 작은 version을 보내면 무시한다")
    void sameOrOlderVersionIsIgnored() {
        confService.applyAvailability(sessionId, true, 5);
        confService.applyAvailability(sessionId, false, 5);
        confService.applyAvailability(sessionId, false, 4);

        assertThat(redis.opsForHash().get(key("conf"), "soldOut")).isEqualTo("1");
    }

    @Test
    @DisplayName("[QUE-8] 회차 설정이 매진 이벤트보다 늦게 와도 매진 상태가 유지되고, 설정 이벤트는 매진 version에 영향이 없다")
    void availabilityBeforeSessionConf() {
        confService.applyAvailability(sessionId, true, 1);
        confService.apply(new SessionConf(sessionId, Type.UPSERT, 1000, System.currentTimeMillis() - 1000, 3, 300));

        QueueService.Entered entered = queue.enter(sessionId, 1);

        assertThat(entered.response().status()).isEqualTo("SOLD_OUT");
        assertThat(redis.opsForHash().get(key("conf"), "soldOutVer")).isEqualTo("1");
    }

    @Test
    @DisplayName("[QUE-8] 중지·삭제된 회차는 매진 여부와 상관없이 판매 중이 아닌 것으로 응답한다")
    void terminalSessionIgnoresSoldOut() {
        putConf(-1000, 3);
        confService.applyAvailability(sessionId, true, 1);
        confService.apply(new SessionConf(sessionId, Type.SUSPEND, 2000, 0, 0, 0));

        assertThatThrownBy(() -> queue.enter(sessionId, 1)).isInstanceOf(BusinessException.class);
        assertThat(admission.admitAll(startOfSecond().plusSeconds(1))).isZero();
    }

    @Test
    @DisplayName("[QUE-8] 매진 중 폴링 간격(최대 6초)대로 갱신된 대기자는 매진이 풀린 직후 지워지지 않고 입장한다")
    void pollingDuringSoldOutKeepsWaiterAdmittable() {
        putConf(-1000, 2);
        enterUsers(1, 4);
        Instant t0 = startOfSecond();
        confService.applyAvailability(sessionId, true, 1);
        // 마지막 폴링이 최대 간격(5초 + 지터 20%)만큼 전이다
        pollUntil(1, 4, t0.minusSeconds(6));

        confService.applyAvailability(sessionId, false, 2);

        assertThat(admission.admitAll(t0)).isEqualTo(2);
        assertThat(isActive(1) && isActive(2)).isTrue();
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(2);
    }

    @Test
    @DisplayName("[QUE-8] 15초 넘게 폴링하지 않은 대기자는 매진이 풀린 뒤 입장 대신 지워지고 다음 사람이 입장한다")
    void stalePollerIsDroppedAfterRelease() {
        putConf(-1000, 1);
        enterUsers(1, 3);
        Instant t0 = startOfSecond();
        confService.applyAvailability(sessionId, true, 1);
        pollUntil(1, 1, t0.minusSeconds(16));
        pollUntil(2, 3, t0.minusSeconds(3));

        confService.applyAvailability(sessionId, false, 2);

        assertThat(admission.admitAll(t0)).isEqualTo(1);
        assertThat(isActive(1)).isFalse();
        assertThat(isActive(2)).isTrue();
        assertThat(redis.opsForZSet().score(key("waiting"), "1")).isNull();
    }
}
