-- 판매하기로 등록한 공연(ADM-1). 관리자 API와 이벤트는 KOPIS ID 대신 이 숫자 ID를 쓴다.
CREATE TABLE sale_performance (
    id         BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    mt20id     VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_sale_performance_mt20id UNIQUE (mt20id)
);

-- 판매 회차(ADM-2). 판매 상태는 suspended와 시각으로 계산한다.
CREATE TABLE session (
    id                   BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    performance_id       BIGINT      NOT NULL,
    start_at             DATETIME(6) NOT NULL,
    sale_open_at         DATETIME(6) NOT NULL,
    admission_per_second INT         NOT NULL,
    max_active_users     INT         NOT NULL,
    suspended            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    INDEX idx_session_performance (performance_id),
    CONSTRAINT fk_session_performance FOREIGN KEY (performance_id) REFERENCES sale_performance (id)
);

CREATE TABLE session_price (
    session_id BIGINT      NOT NULL,
    grade      VARCHAR(10) NOT NULL,
    price      INT         NOT NULL,
    PRIMARY KEY (session_id, grade),
    CONSTRAINT fk_session_price_session FOREIGN KEY (session_id) REFERENCES session (id) ON DELETE CASCADE
);
