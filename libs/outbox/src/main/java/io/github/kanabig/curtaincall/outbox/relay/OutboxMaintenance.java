package io.github.kanabig.curtaincall.outbox.relay;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 발행이 끝난 행을 보관 기간이 지나면 지운다. */
public class OutboxMaintenance {

    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenance.class);
    private static final int DELETE_LIMIT = 1000;

    private final OutboxRepository repository;
    private final Duration retention;

    public OutboxMaintenance(OutboxRepository repository, Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    public int deleteExpired() {
        int total = 0;
        int deleted;
        do {
            deleted = repository.deletePublishedOlderThan(retention, DELETE_LIMIT);
            total += deleted;
        } while (deleted == DELETE_LIMIT);
        if (total > 0) {
            log.info("발행이 끝난 outbox 행 {}건을 지웠다", total);
        }
        return total;
    }
}
