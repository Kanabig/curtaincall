package io.github.kanabig.curtaincall.queue.service;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Redis 호출이 실패하면 열리고, 열려 있는 동안 요청은 Redis를 부르지 않고 바로 503을 받는다(NFR-11). 요청 경로에는 시험 호출이 없다. 닫는 것은 요청 밖에서 도는
 * {@link RedisCircuitProber}가 PING에 성공했을 때뿐이다. 그래서 장애 중 요청 지연이 Redis 타임아웃에 묶이지 않는다.
 *
 * <p>상태는 {@link AtomicBoolean} 하나이고 열기·닫기는 CAS라 동시에 겹쳐도 한 번만 성공한다. 열리기 전에 시작한 요청의 실패가 뒤늦게 와도 이미 열려 있으면 새로 여는
 * 것으로 세지 않는다. 닫힌 직후 다시 실패하면 다시 열린다. 열림 여부를 보는 gauge는 없고 열린 횟수 counter가 있다.
 */
@Component
public class RedisCircuit {

    private final AtomicBoolean open = new AtomicBoolean();

    public boolean isOpen() {
        return open.get();
    }

    /** @return 이번 호출로 새로 열렸으면 true. 이미 열려 있으면 false */
    public boolean open() {
        return open.compareAndSet(false, true);
    }

    /** @return 이번 호출로 닫았으면 true. 이미 닫혀 있으면 false */
    public boolean close() {
        return open.compareAndSet(true, false);
    }
}
