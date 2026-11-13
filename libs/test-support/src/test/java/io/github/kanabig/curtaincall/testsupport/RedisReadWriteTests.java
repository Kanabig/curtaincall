package io.github.kanabig.curtaincall.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
@Import(TestContainersConfig.class)
class RedisReadWriteTests {

    @Autowired
    StringRedisTemplate redisTemplate;

    @Test
    @DisplayName("Redis 8에 쓴 값을 읽고 ZSET 순위를 조회한다")
    void writeAndRead() {
        redisTemplate.opsForValue().set("sample:key", "value", Duration.ofMinutes(1));
        redisTemplate.opsForZSet().add("sample:queue", "user-1", 1);
        redisTemplate.opsForZSet().add("sample:queue", "user-2", 2);

        assertThat(redisTemplate.opsForValue().get("sample:key")).isEqualTo("value");
        assertThat(redisTemplate.opsForZSet().rank("sample:queue", "user-2")).isEqualTo(1L);
    }
}
