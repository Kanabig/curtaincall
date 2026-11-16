package io.github.kanabig.curtaincall.searchassist.service;

import java.time.Clock;
import java.time.LocalDate;

/** 하루 LLM 호출 한도. 인스턴스 메모리 카운터라 재시작하면 0으로 돌아간다. KST 자정에 초기화된다. */
public class DailyQuota {

    private final Clock clock;
    private final int limit;
    private LocalDate day;
    private int used;

    public DailyQuota(Clock clock, int limit) {
        this.clock = clock;
        this.limit = limit;
    }

    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= limit) {
            return false;
        }
        used++;
        return true;
    }
}
