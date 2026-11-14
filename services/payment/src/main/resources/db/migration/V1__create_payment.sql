CREATE TABLE payment (
    payment_id          VARCHAR(40)  NOT NULL PRIMARY KEY,
    order_id            VARCHAR(64)  NOT NULL,
    user_id             VARCHAR(64)  NOT NULL,
    amount              BIGINT       NOT NULL,
    card_token          VARCHAR(64)  NOT NULL,
    status              VARCHAR(20)  NOT NULL,
    pg_tid              VARCHAR(64)  NULL,
    failure_reason      VARCHAR(30)  NULL,
    approved_at         DATETIME(6)  NULL,
    inquiry_started_at  DATETIME(6)  NULL,
    next_inquiry_at     DATETIME(6)  NULL,
    inquiry_attempts    INT          NOT NULL DEFAULT 0,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    -- 같은 주문은 승인을 한 번만 시도한다(PAY-3)
    CONSTRAINT uk_payment_order UNIQUE (order_id),
    -- 거래 조회 대상 선정과 UNKNOWN 건수
    INDEX idx_payment_status_inquiry (status, next_inquiry_at)
);
