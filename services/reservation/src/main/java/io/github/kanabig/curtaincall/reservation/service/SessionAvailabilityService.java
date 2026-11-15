package io.github.kanabig.curtaincall.reservation.service;

import io.github.kanabig.curtaincall.outbox.relay.OutboxWriter;
import io.github.kanabig.curtaincall.reservation.config.OrderTopicsConfig;
import io.github.kanabig.curtaincall.reservation.repository.SessionAvailabilityRepository;
import io.github.kanabig.curtaincall.reservation.repository.SessionAvailabilityRepository.Published;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * [QUE-8] 판매 중인 회차의 매진 여부를 주기적으로 점검해 바뀔 때만 {@code SessionAvailabilityChanged}를 outbox로 낸다.
 *
 * <p>확정 트랜잭션에서 남은 좌석을 세면 마지막 두 건이 동시에 커밋될 때 서로의 좌석을 미확정으로 보고 둘 다 놓칠 수 있어, 확정 경로는 건드리지 않고 여기서 센다. 판정이 바뀐
 * 것으로 보이면 {@code session_stock} 행을 잠그고 다시 세어 version을 1씩 올린 뒤 같은 트랜잭션에서 outbox에 쓴다. 그래서 인스턴스가 여럿이어도 한 회차의 이벤트가 중복
 * 없이 version 순서대로 나간다. 잠금은 점검끼리만 겹치고 점유·확정 경로는 이 행을 읽지 않는다.
 */
@Service
public class SessionAvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(SessionAvailabilityService.class);

    private final SessionAvailabilityRepository repository;
    private final OutboxWriter outbox;
    private final TransactionTemplate tx;
    private final Clock clock;

    public SessionAvailabilityService(
            SessionAvailabilityRepository repository, OutboxWriter outbox, TransactionTemplate tx) {
        this.repository = repository;
        this.outbox = outbox;
        this.tx = tx;
        this.clock = Clock.systemUTC();
    }

    /** @return 이번에 이벤트를 낸 회차 수 */
    public int checkAll() {
        int published = 0;
        for (Published last : repository.findSelling(clock.instant())) {
            try {
                // 잠금 없이 먼저 세어 바뀌지 않았으면 끝낸다. 대부분의 회차는 여기서 끝나 행 잠금을 잡지 않는다
                if (repository.isSoldOut(last.sessionId()) != last.soldOut() && publishIfChanged(last.sessionId())) {
                    published++;
                }
            } catch (RuntimeException e) {
                log.warn("매진 점검에 실패했다. 다음 주기에 다시 시도한다: sessionId={}", last.sessionId(), e);
            }
        }
        return published;
    }

    private boolean publishIfChanged(long sessionId) {
        Boolean done = tx.execute(status -> {
            Published locked = repository.lock(sessionId).orElse(null);
            if (locked == null) {
                return false;
            }
            boolean soldOut = repository.isSoldOut(sessionId);
            if (soldOut == locked.soldOut()) {
                return false;
            }
            long version = locked.version() + 1;
            repository.update(sessionId, soldOut, version);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("soldOut", soldOut);
            payload.put("version", version);
            outbox.append(
                    OrderTopicsConfig.SESSION_TOPIC, String.valueOf(sessionId), "SessionAvailabilityChanged", payload);
            log.info("회차 매진 상태가 바뀌었다: sessionId={} soldOut={} version={}", sessionId, soldOut, version);
            return true;
        });
        return Boolean.TRUE.equals(done);
    }
}
