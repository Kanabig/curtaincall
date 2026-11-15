package io.github.kanabig.curtaincall.queue;

import io.github.kanabig.curtaincall.testsupport.TestContainersConfig;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 컨테이너는 JVM 안에서 공유하므로 테스트마다 다른 회차 ID를 쓰고, 끝나면 그 키를 지운다. */
@SpringBootTest(
        properties = {
            "spring.kafka.listener.auto-startup=false",
            // 입장 처리는 테스트가 시각을 넘겨 직접 부른다
            "curtaincall.queue.scheduling.enabled=false",
            "curtaincall.jwt.entry-secret=" + QueueIntegrationTest.ENTRY_SECRET
        })
@Import(TestContainersConfig.class)
public abstract class QueueIntegrationTest {

    public static final String ENTRY_SECRET = "test-entry-secret-0123456789abcdef-test";

    private static final AtomicLong NEXT_SESSION = new AtomicLong(1_000_000 + (System.nanoTime() % 100_000) * 100);

    @Autowired
    protected StringRedisTemplate redis;

    protected final long sessionId = NEXT_SESSION.addAndGet(1);

    @AfterEach
    void cleanKeys() {
        Set<String> keys = redis.keys("q:" + sessionId + ":*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        redis.opsForSet().remove("q:open", String.valueOf(sessionId));
    }

    /** 오픈 시각이 openAtOffsetMs만큼 지난(음수) 또는 남은(양수) ON_SALE 회차를 만든다. */
    protected void putConf(long openAtOffsetMs, int admissionPerSecond) {
        putConf(openAtOffsetMs, admissionPerSecond, 300);
    }

    /** {@link #putConf(long, int)}에 활성 상한을 더하고, 입장 처리 대상(q:open)에도 올린다. */
    protected void putConf(long openAtOffsetMs, int admissionPerSecond, int maxActiveUsers) {
        redis.opsForSet().add("q:open", String.valueOf(sessionId));
        long openAt = System.currentTimeMillis() + openAtOffsetMs;
        String key = "q:" + sessionId + ":conf";
        redis.opsForHash()
                .putAll(
                        key,
                        java.util.Map.of(
                                "status", "ON_SALE",
                                "saleOpenAt", String.valueOf(openAt),
                                "admissionPerSecond", String.valueOf(admissionPerSecond),
                                "maxActiveUsers", String.valueOf(maxActiveUsers),
                                "ver", "1"));
    }
}
