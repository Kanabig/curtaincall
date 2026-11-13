package io.github.kanabig.curtaincall.outbox.consumer;

import java.time.Duration;

/** 보관 기간이 지난 처리 기록을 지운다. 기간은 재배달이 일어날 수 있는 시간보다 길어야 한다. */
public class ProcessedEventMaintenance {

    private static final int DELETE_LIMIT = 1000;

    private final ProcessedEventRepository repository;
    private final Duration retention;

    public ProcessedEventMaintenance(ProcessedEventRepository repository, Duration retention) {
        this.repository = repository;
        this.retention = retention;
    }

    public void deleteExpired() {
        int deleted;
        do {
            deleted = repository.deleteOlderThan(retention, DELETE_LIMIT);
        } while (deleted == DELETE_LIMIT);
    }
}
