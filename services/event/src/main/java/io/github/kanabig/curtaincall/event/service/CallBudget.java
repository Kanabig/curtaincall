package io.github.kanabig.curtaincall.event.service;

import io.github.kanabig.curtaincall.event.repository.KopisCallLogRepository;
import java.time.Clock;
import java.time.LocalDate;

/**
 * 한 번의 동기화가 쓸 수 있는 KOPIS 호출 수. 실행당 상한(runLimit)과 하루 장부(dailyLimit)를 둘 다 지킨다. 장부는 수동 동기화와 개발용 호출도 센다. 외부 호출이
 * 아닌 클라이언트(fixture)는 세지 않는다.
 */
final class CallBudget {

    private final KopisCallLogRepository ledger;
    private final Clock clock;
    private final boolean metered;
    private final int runLimit;
    private final int dailyLimit;
    private int used;

    CallBudget(KopisCallLogRepository ledger, Clock clock, boolean metered, int runLimit, int dailyLimit) {
        this.ledger = ledger;
        this.clock = clock;
        this.metered = metered;
        this.runLimit = runLimit;
        this.dailyLimit = dailyLimit;
    }

    /** 호출 하나를 쓴다. 상한에 닿았으면 쓰지 않고 false. */
    boolean tryAcquire() {
        if (!metered) {
            return true;
        }
        if (used >= runLimit || !ledger.tryIncrement(LocalDate.now(clock), dailyLimit)) {
            return false;
        }
        used++;
        return true;
    }

    int used() {
        return used;
    }
}
