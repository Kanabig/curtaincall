package io.github.kanabig.curtaincall.queue.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Redis 호출 실패 뒤 일정 시간 동안 호출을 건너뛰게 하는 단순 서킷. 장애 중에 요청마다 Redis 타임아웃(500ms)을 기다리면
 * 톰캣 스레드가 거기 묶이므로, 열려 있는 동안은 바로 503을 돌려준다. 시간이 지나면 요청 하나만 Redis를 불러 보고(half-open)
 * 성공하면 닫는다.
 */
class RedisCircuit {

    private final long openNanos;
    private final LongSupplier nanoTime;
    private final AtomicBoolean open = new AtomicBoolean();
    private final AtomicBoolean probing = new AtomicBoolean();

    /** 이 시각(nanoTime)까지는 Redis를 부르지 않는다. */
    private final AtomicLong skipUntil = new AtomicLong();

    RedisCircuit(Duration openFor, LongSupplier nanoTime) {
        this.openNanos = openFor.toNanos();
        this.nanoTime = nanoTime;
    }

    /** 이번 호출이 Redis를 불러도 되는지. true면 결과를 {@link #onSuccess}·{@link #onFailure}로 알려야 한다. */
    boolean tryAcquire() {
        if (!open.get()) {
            return true;
        }
        return nanoTime.getAsLong() - skipUntil.get() >= 0 && probing.compareAndSet(false, true);
    }

    void onSuccess() {
        open.set(false);
        probing.set(false);
    }

    /** @return 이번 실패로 서킷이 새로 열렸거나(닫혀 있었음) 시험 호출이 실패해 다시 열렸으면 true. 이미 열린 뒤 늦게 도착한 실패는 false */
    boolean onFailure() {
        boolean opened = !open.get() || probing.get();
        skipUntil.set(nanoTime.getAsLong() + openNanos);
        open.set(true);
        probing.set(false);
        return opened;
    }

    /** Redis와 무관한 예외로 시험 호출이 끝났을 때 다음 요청이 다시 시험할 수 있게 풀어 준다. */
    void release() {
        probing.set(false);
    }

    boolean isOpen() {
        return open.get();
    }
}
