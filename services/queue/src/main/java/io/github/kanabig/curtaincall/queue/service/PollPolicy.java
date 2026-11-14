package io.github.kanabig.curtaincall.queue.service;

import java.util.function.DoubleSupplier;

/** [QUE-3] 앞 사람 수에 따라 다음 폴링 간격을 1·3·5초로 정하고 ±20% 지터를 붙인다(ADR-0007). */
public class PollPolicy {

    static final long NEAR_MS = 1000;
    static final long MID_MS = 3000;
    static final long FAR_MS = 5000;
    private static final double JITTER = 0.2;

    private final DoubleSupplier random;

    public PollPolicy(DoubleSupplier random) {
        this.random = random;
    }

    public long pollAfterMs(long ahead, long admissionPerSecond) {
        long perSecond = Math.max(1, admissionPerSecond);
        long base;
        if (ahead <= perSecond * 10) {
            base = NEAR_MS;
        } else if (ahead <= perSecond * 60) {
            base = MID_MS;
        } else {
            base = FAR_MS;
        }
        double factor = 1 - JITTER + 2 * JITTER * random.getAsDouble();
        return Math.round(base * factor);
    }

    public long etaSeconds(long ahead, long admissionPerSecond) {
        return ahead / Math.max(1, admissionPerSecond);
    }
}
