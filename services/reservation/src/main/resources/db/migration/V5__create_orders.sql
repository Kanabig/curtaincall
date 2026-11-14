-- 주문. status가 Saga 상태다. hold_id는 점유 하나당 주문 하나를 보장한다.
CREATE TABLE orders (
    order_id           VARCHAR(40)  NOT NULL PRIMARY KEY,
    hold_id            CHAR(36)     NOT NULL,
    user_id            BIGINT       NOT NULL,
    session_id         BIGINT       NOT NULL,
    seat_ids           JSON         NOT NULL,
    amount             BIGINT       NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    hold_expires_at    DATETIME(6)  NOT NULL,
    card_token         VARCHAR(100) NULL,
    payment_id         VARCHAR(64)  NULL,
    -- ApprovePayment를 보낸 횟수와 다음 재전송 시각. 재전송을 멈춘 주문은 next_resend_at이 NULL이다
    command_sent_count INT          NOT NULL DEFAULT 0,
    next_resend_at     DATETIME(6)  NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_orders_hold UNIQUE (hold_id),
    INDEX idx_orders_resend (status, next_resend_at),
    INDEX idx_orders_pending_expiry (status, hold_expires_at)
);

-- 확정된 예매. 주문 하나당 하나.
CREATE TABLE reservation (
    reservation_id VARCHAR(40) NOT NULL PRIMARY KEY,
    order_id       VARCHAR(40) NOT NULL,
    user_id        BIGINT      NOT NULL,
    session_id     BIGINT      NOT NULL,
    seat_ids       JSON        NOT NULL,
    amount         BIGINT      NOT NULL,
    status         VARCHAR(20) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_reservation_order UNIQUE (order_id),
    INDEX idx_reservation_user (user_id, created_at)
);

-- Idempotency-Key로 처리한 요청의 첫 결과. response는 처리가 끝나야 채워진다.
CREATE TABLE idempotency_key (
    user_id    BIGINT       NOT NULL,
    scope      VARCHAR(80)  NOT NULL,
    idem_key   CHAR(36)     NOT NULL,
    response   JSON         NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, scope, idem_key),
    INDEX idx_idempotency_created (created_at)
);
