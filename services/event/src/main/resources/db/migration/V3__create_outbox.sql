-- 발행하는 서비스가 자기 마이그레이션(V<n>__create_outbox.sql)으로 복사해 쓴다.
CREATE TABLE outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    topic        VARCHAR(255) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    event_id     CHAR(36)     NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    request_id   VARCHAR(64)  NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    published_at DATETIME(6)  NULL,
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    -- 미발행 행 조회(published_at IS NULL ORDER BY id)와 발행된 행 정리
    INDEX idx_outbox_published (published_at, id),
    -- 같은 키의 앞선 미발행 행 확인
    INDEX idx_outbox_key (message_key, id)
);
