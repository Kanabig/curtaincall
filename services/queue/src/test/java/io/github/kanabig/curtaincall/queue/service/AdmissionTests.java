package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import io.github.kanabig.curtaincall.queue.config.QueueProperties;
import io.github.kanabig.curtaincall.queue.dto.QueueStatusResponse;
import io.github.kanabig.curtaincall.queue.repository.QueueRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** [QUE-4~6] 입장 처리, 입장 토큰, 이탈 정리. 시각은 테스트가 넘기므로 기다리지 않는다. */
class AdmissionTests extends QueueIntegrationTest {

    @Autowired
    AdmissionService admission;

    @Autowired
    QueueService queue;

    @Autowired
    QueueRepository repository;

    @Autowired
    QueueProperties properties;

    private String key(String name) {
        return "q:" + sessionId + ":" + name;
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

    private boolean isActive(long userId) {
        return redis.opsForZSet().score(key("active"), String.valueOf(userId)) != null;
    }

    @Test
    @DisplayName("[QUE-4] 초당 설정 인원만큼 앞 순번부터 입장시키고, 같은 초에 다시 돌아도 더 들이지 않는다")
    void admitsInOrderPerSecond() {
        putConf(-1000, 3);
        enterUsers(1, 8);
        Instant t0 = startOfSecond();

        assertThat(admission.admitAll(t0)).isEqualTo(3);
        assertThat(admission.admitAll(t0.plusMillis(300))).isZero();
        assertThat(isActive(1) && isActive(2) && isActive(3)).isTrue();
        assertThat(isActive(4)).isFalse();

        assertThat(admission.admitAll(t0.plusSeconds(1))).isEqualTo(3);
        assertThat(isActive(6)).isTrue();
        assertThat(isActive(7)).isFalse();
        assertThat(admission.admitAll(t0.plusSeconds(2))).isEqualTo(2);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isZero();
    }

    @Test
    @DisplayName("[QUE-4] 활성 수가 상한에 닿으면 더 들이지 않는다")
    void neverExceedsMaxActive() {
        putConf(-1000, 50, 120);
        enterUsers(1, 400);
        Instant t0 = startOfSecond();

        for (int s = 0; s < 6; s++) {
            admission.admitAll(t0.plusSeconds(s));
            assertThat(activeCount()).isLessThanOrEqualTo(120);
        }
        assertThat(activeCount()).isEqualTo(120);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(280);
    }

    @Test
    @DisplayName("[QUE-4] 같은 초에 여러 인스턴스가 동시에 돌아도 그 초의 입장은 한 번이다")
    void sameSecondRunsOnce() throws Exception {
        putConf(-1000, 10, 10_000);
        enterUsers(1, 300);
        // 인스턴스마다 AdmissionService가 따로 돈다고 보고, 같은 초를 스레드 16개가 동시에 처리한다
        List<AdmissionService> instances = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            instances.add(new AdmissionService(repository, properties));
        }
        Instant t0 = startOfSecond();
        for (int second = 0; second < 5; second++) {
            Instant now = t0.plusSeconds(second);
            long admitted = parallel(instances, a -> a.admitAll(now));
            assertThat(admitted).as("%d번째 초", second).isEqualTo(10);
        }
        assertThat(activeCount()).isEqualTo(50);
    }

    @Test
    @DisplayName("[QUE-4] 초가 다른 입장 처리가 동시에 겹쳐도 활성 수는 상한을 넘지 않는다")
    void concurrentTicksRespectMax() throws Exception {
        putConf(-1000, 50, 100);
        enterUsers(1, 1000);
        Instant t0 = startOfSecond();
        List<Integer> seconds = new ArrayList<>();
        for (int s = 0; s < 40; s++) {
            seconds.add(s);
        }
        parallel(seconds, s -> admission.admitAll(t0.plusSeconds(s)));
        assertThat(activeCount()).isEqualTo(100);
        assertThat(redis.opsForZSet().zCard(key("waiting"))).isEqualTo(900);
    }

    @Test
    @DisplayName("[QUE-5, QUE-6] 입장 토큰 만료 시각이 지나면 활성에서 빠지고 그 자리에 다음 대기자가 들어온다")
    void expiredActiveFreesSlot() {
        putConf(-1000, 10, 2);
        enterUsers(1, 4);
        Instant t0 = startOfSecond();
        admission.admitAll(t0);
        assertThat(isActive(1) && isActive(2)).isTrue();

        // 만료 1초 전까지는 자리가 차 있다
        admission.admitAll(t0.plus(properties.entryTtl()).minusSeconds(1));
        assertThat(isActive(3)).isFalse();

        admission.admitAll(t0.plus(properties.entryTtl()).plusSeconds(1));
        assertThat(isActive(1)).isFalse();
        assertThat(isActive(3) && isActive(4)).isTrue();
    }

    @Test
    @DisplayName("[QUE-6] 폴링이 60초 넘게 없는 대기자는 제거되고 활성 목록은 정리되며, 최근에 폴링한 사람은 남는다")
    void removesStaleWaiters() {
        putConf(-1000, 1, 300);
        enterUsers(1, 3);
        Instant t0 = startOfSecond();
        long nowMs = t0.toEpochMilli();
        // 1번은 61초 전, 2번은 59초 전에 마지막으로 폴링했다
        redis.opsForZSet().add(key("seen"), "1", nowMs - 61_000);
        redis.opsForZSet().add(key("seen"), "2", nowMs - 59_000);
        redis.opsForZSet().add(key("seen"), "3", nowMs);

        assertThat(admission.removeStale(t0)).isEqualTo(1);
        assertThat(redis.opsForZSet().score(key("waiting"), "1")).isNull();
        assertThat(redis.opsForZSet().score(key("seen"), "1")).isNull();
        assertThat(redis.opsForZSet().score(key("waiting"), "2")).isNotNull();

        // 빠진 사람이 앞자리를 잡고 있지 않으므로 다음 입장은 2번이다
        admission.admitAll(t0);
        assertThat(isActive(2)).isTrue();
        assertThat(isActive(1)).isFalse();

        // 시간이 더 지나면 폴링하지 않은 3번까지 빠진다. 입장한 2번은 대기 정리 대상이 아니다
        assertThat(admission.removeStale(t0.plusSeconds(61))).isEqualTo(1);
        assertThat(isActive(2)).isTrue();
    }

    @Test
    @DisplayName("[QUE-6] 대기 포기는 대기열에서 바로 빠지고, 없는 사용자에게 다시 해도 오류가 없다")
    void leave() {
        putConf(-1000, 1);
        enterUsers(1, 2);
        queue.leave(sessionId, 1);
        queue.leave(sessionId, 1);
        assertThat(queue.status(sessionId, 1).status()).isEqualTo("EXPIRED");
        assertThat(queue.status(sessionId, 2).ahead()).isZero();
    }

    @Test
    @DisplayName("[QUE-5] 입장하면 sub, sid, exp를 담은 HS256 토큰을 받고 만료는 활성 만료 시각과 같다")
    void issuesEntryToken() {
        putConf(-1000, 5);
        enterUsers(7, 7);
        admission.admitAll(startOfSecond());

        QueueStatusResponse admitted = queue.status(sessionId, 7);
        assertThat(admitted.status()).isEqualTo("ADMITTED");
        Jwt jwt = decoder(ENTRY_SECRET).decode(admitted.entryToken());
        assertThat(jwt.getSubject()).isEqualTo("7");
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(String.valueOf(sessionId));
        double expMs = redis.opsForZSet().score(key("active"), "7");
        assertThat(jwt.getExpiresAt().toEpochMilli()).isEqualTo(((long) expMs / 1000) * 1000);
        assertThat(Duration.between(Instant.now(), jwt.getExpiresAt()))
                .isBetween(Duration.ofMinutes(9).plusSeconds(55), Duration.ofMinutes(10));

        // 다시 조회해도 같은 입장이고 같은 만료다
        assertThat(decoder(ENTRY_SECRET)
                        .decode(queue.status(sessionId, 7).entryToken())
                        .getExpiresAt())
                .isEqualTo(jwt.getExpiresAt());
    }

    @Test
    @DisplayName("[QUE-5] 다른 키로 검증하면 입장 토큰은 거부된다")
    void tokenIsBoundToKey() {
        putConf(-1000, 5);
        enterUsers(7, 7);
        admission.admitAll(startOfSecond());
        String token = queue.status(sessionId, 7).entryToken();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> decoder("another-secret-0123456789abcdef-xxxx").decode(token))
                .isInstanceOf(org.springframework.security.oauth2.jwt.JwtException.class);
    }

    @Test
    @DisplayName("[QUE-4] 판매 중이 아닌 회차는 입장 처리 대상에서 빠진다")
    void suspendedSessionIsNotAdmitted() {
        putConf(-1000, 5);
        enterUsers(1, 3);
        redis.opsForSet().remove("q:open", String.valueOf(sessionId));
        assertThat(admission.admitAll(startOfSecond())).isZero();
        assertThat(activeCount()).isZero();
    }

    /** 초 경계에 걸려 같은 초 판정이 흔들리지 않도록 초의 시작으로 맞춘다. */
    private static Instant startOfSecond() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    }

    private static NimbusJwtDecoder decoder(String secret) {
        return NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private static <T> long parallel(List<T> items, java.util.function.ToLongFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(items.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (T item : items) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.applyAsLong(item);
                }));
            }
            start.countDown();
            long total = 0;
            for (Future<Long> f : futures) {
                total += f.get();
            }
            return total;
        } finally {
            pool.shutdownNow();
        }
    }
}
