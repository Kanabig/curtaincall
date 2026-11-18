package io.github.kanabig.curtaincall.gateway.filter;

import io.github.kanabig.curtaincall.gateway.config.EntryActivityProperties;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 입장 토큰이 서명상 유효해도 queue가 활성 목록에서 뺀 사용자(활동이 없어 회수된 경우)는 거절해야 하고, 통과한 요청은 마지막 활동으로 남겨야 한다. 둘을 Lua 하나로 처리해
 * 요청당 Redis 호출은 1회다. 키 이름은 queue의 QueueKeys와 맞춘다.
 *
 * <p>Redis 호출이 실패하거나 제한 시간을 넘으면 통과시킨다(fail-open, NFR-11). 이미 입장한 사용자의 점유·결제가 이어져야 하기 때문이다. 이 동안은 활동 기록도, queue의 회수도
 * 멈춘다(같은 Redis). 실패하면 {@code circuitOpen} 동안은 Redis를 부르지 않는다.
 */
@Component
public class EntryActivity {

    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<Long> SCRIPT = script();

    private final ReactiveStringRedisTemplate redis;
    private final EntryActivityProperties properties;
    private final MeterRegistry meters;
    private final RedisCircuit circuit;

    public EntryActivity(ReactiveStringRedisTemplate redis, EntryActivityProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.properties = properties;
        this.meters = meters;
        this.circuit = new RedisCircuit(
                "entry-activity",
                properties.circuitOpen(),
                meters,
                "입장 활동 Redis 호출 실패",
                "입장 활동 Redis 호출이 복구되어 활성 소속을 다시 확인한다");
    }

    /** @return 활성 목록에 있으면(또는 확인할 수 없으면) true, queue가 이미 뺀 사용자면 false */
    public Mono<Boolean> touch(String sessionId, String userId) {
        return circuit.call(
                () -> redis.execute(
                                SCRIPT,
                                List.of("q:" + sessionId + ":active", "q:" + sessionId + ":activity"),
                                List.of(
                                        userId,
                                        Long.toString(
                                                properties.recordInterval().toMillis())))
                        .next()
                        .map(result -> result == 1L),
                properties.timeout(),
                true,
                () -> meters.counter("gateway.entry-activity.failopen").increment());
    }

    @SuppressWarnings("rawtypes")
    private static DefaultRedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/entry-activity.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
