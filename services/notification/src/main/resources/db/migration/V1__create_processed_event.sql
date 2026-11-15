-- 소비하는 서비스가 자기 마이그레이션(V<n>__create_processed_event.sql)으로 복사해 쓴다.
CREATE TABLE processed_event (
    event_id     CHAR(36)    NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_processed_event_at (processed_at)
);
