-- 환불(PAY-5). 취소 사유, 취소 호출 횟수, 다음 취소 시도 시각
ALTER TABLE payment
    ADD COLUMN cancel_reason   VARCHAR(20) NULL,
    ADD COLUMN cancel_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_cancel_at  DATETIME(6) NULL,
    ADD COLUMN cancelled_at    DATETIME(6) NULL,
    ADD INDEX idx_payment_status_cancel (status, next_cancel_at);
