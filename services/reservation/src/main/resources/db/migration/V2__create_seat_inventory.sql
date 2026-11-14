-- 회차별 좌석 재고. seat_id는 event의 공연장 배치 템플릿 좌석 ID다.
CREATE TABLE seat_inventory (
    session_id BIGINT      NOT NULL,
    seat_id    BIGINT      NOT NULL,
    status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    grade      VARCHAR(20) NOT NULL,
    price      INT         NOT NULL,
    hold_id    CHAR(36)    NULL,
    holder_id  BIGINT      NULL,
    expires_at DATETIME(6) NULL,
    version    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, seat_id),
    INDEX idx_seat_inventory_hold (hold_id)
);

-- 회차의 재고 생성 상태. PENDING은 재고를 만들어야 하는 회차, READY는 재고 행이 모두 만들어진 회차다.
-- next_attempt_at은 다음 처리 시각이면서 처리 중인 인스턴스가 잡아 둔 임대 만료 시각이다.
CREATE TABLE session_stock (
    session_id      BIGINT       NOT NULL PRIMARY KEY,
    status          VARCHAR(10)  NOT NULL,
    seat_count      INT          NULL,
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    last_error      VARCHAR(200) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    ready_at        DATETIME(6)  NULL,
    INDEX idx_session_stock_due (status, next_attempt_at)
);
