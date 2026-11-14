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

-- 행이 있으면 재고 생성이 끝난 회차다. 재고 행과 같은 트랜잭션에서 넣는다.
CREATE TABLE session_stock (
    session_id BIGINT      NOT NULL PRIMARY KEY,
    seat_count INT         NOT NULL,
    ready_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
