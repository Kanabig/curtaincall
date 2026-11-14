package io.github.kanabig.curtaincall.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.kanabig.curtaincall.queue.QueueIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/** [QUE-2] 진입 번호는 Lua 안에서 INCR로 발급되므로 동시 진입에도 순번이 겹치거나 빠지지 않는다. */
class QueueConcurrencyTests extends QueueIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(QueueConcurrencyTests.class);

    @Autowired
    QueueService service;

    @Test
    @DisplayName("같은 계정이 100건 동시에 진입해도 순번은 하나이고 대기열에는 한 명만 있다")
    void sameUserConcurrently() throws Exception {
        putConf(-1000, 50);

        List<Long> positions = runConcurrently(
                100, i -> service.enter(sessionId, 7L).response().position());

        assertThat(positions).containsOnly(1L);
        assertThat(redis.opsForZSet().zCard("q:" + sessionId + ":waiting")).isEqualTo(1);
        assertThat(redis.opsForValue().get("q:" + sessionId + ":seq")).isEqualTo("1");
    }

    @Test
    @DisplayName("1만 명이 동시에 진입하면 순번은 1부터 1만까지 중복과 누락 없이 나뉜다")
    void tenThousandUsersConcurrently() throws Exception {
        putConf(-1000, 50);
        int users = 10_000;

        long start = System.nanoTime();
        List<Long> positions = runConcurrently(
                users, i -> service.enter(sessionId, 1000L + i).response().position());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Set<Long> distinct = positions.stream().collect(Collectors.toSet());
        assertThat(distinct).hasSize(users);
        assertThat(distinct).allMatch(p -> p >= 1 && p <= users);
        assertThat(redis.opsForZSet().zCard("q:" + sessionId + ":waiting")).isEqualTo(users);
        log.info("10,000 concurrent entries took {} ms", elapsedMs);
    }

    private interface Call {
        Long run(int i);
    }

    private static List<Long> runConcurrently(int count, Call call) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                int n = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.run(n);
                }));
            }
            ready.await();
            go.countDown();
            List<Long> results = new ArrayList<>();
            for (Future<Long> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
