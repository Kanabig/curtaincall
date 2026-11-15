package io.github.kanabig.curtaincall.queue.service;

import io.github.kanabig.curtaincall.queue.config.QueueProperties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Redis 호출이 연속으로 {@code threshold}번 실패하면 열리고, 열려 있는 동안 요청은 Redis를 부르지 않고 바로 503을 받는다(NFR-11). 성공하면 연속 실패 횟수가 0으로
 * 돌아간다(인스턴스의 모든 요청 스레드가 카운터를 공유하므로 엄밀한 연속이 아니라 마지막 성공 이후의 실패 수다). 과부하로 생긴 드문 타임아웃 한 번에는 열리지 않는다. 요청 경로에는 시험 호출이 없다. 닫는 것은 요청 밖에서 도는 {@link RedisCircuitProber}가 PING에 성공했을
 * 때뿐이라, 장애 중 요청 지연이 Redis 타임아웃에 묶이지 않는다.
 *
 * <p>열림 여부는 {@link AtomicBoolean} 하나이고 열기·닫기는 CAS라 동시에 겹쳐도 한 번만 성공한다. 이미 열려 있을 때 늦게 온 실패는 새로 여는 것으로 세지 않는다.
 * 장애 중에 시작해 닫힌 뒤에 타임아웃으로 끝난 요청의 실패는 연속 실패로 세어지므로 서킷을 다시 열 수 있다. 열림 여부를 보는 gauge는 없고 열린 횟수 counter가 있다.
 */
@Component
public class RedisCircuit {

    private final int threshold;
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    @Autowired
    public RedisCircuit(QueueProperties properties) {
        this(properties.redisCircuitFailures());
    }

    public RedisCircuit(int threshold) {
        this.threshold = Math.max(1, threshold);
    }

    public boolean isOpen() {
        return open.get();
    }

    /** 호출이 성공했다. 연속 실패 횟수를 0으로 되돌린다. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
    }

    /** @return 이번 실패로 서킷이 새로 열렸으면 true. 한도에 못 미쳤거나 이미 열려 있으면 false */
    public boolean recordFailure() {
        if (consecutiveFailures.incrementAndGet() < threshold) {
            return false;
        }
        return open.compareAndSet(false, true);
    }

    /** @return 이번 호출로 닫았으면 true. 이미 닫혀 있으면 false */
    public boolean close() {
        consecutiveFailures.set(0);
        return open.compareAndSet(true, false);
    }
}
