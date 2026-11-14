package io.github.kanabig.curtaincall.outbox.consumer;

import java.time.Duration;
import org.springframework.transaction.support.TransactionTemplate;

/** 보관 기간이 지난 처리 기록을 지운다. 기간은 재배달이 일어날 수 있는 시간보다 길어야 한다. */
public class ProcessedEventMaintenance {

    private static final int DELETE_LIMIT = 1000;

    private final ProcessedEventRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Duration retention;

    public ProcessedEventMaintenance(
            ProcessedEventRepository repository, TransactionTemplate transactionTemplate, Duration retention) {
        this.transactionTemplate = transactionTemplate;
        this.repository = repository;
        this.retention = retention;
    }

    public void deleteExpired() {
        int deleted;
        do {
            Integer result = transactionTemplate.execute(status -> repository.deleteOlderThan(retention, DELETE_LIMIT));
            deleted = result == null ? 0 : result;
        } while (deleted == DELETE_LIMIT);
    }
}
