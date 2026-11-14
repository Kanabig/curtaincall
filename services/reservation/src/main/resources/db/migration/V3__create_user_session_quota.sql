-- 회차당 1인 매수 한도를 지키기 위한 직렬화용 행. 같은 사용자의 점유 요청만 이 행에서 줄을 선다.
CREATE TABLE user_session_quota (
    user_id    BIGINT NOT NULL,
    session_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, session_id)
);

-- 사용자의 보유 좌석(점유 + 판매) 수를 세는 조회용
CREATE INDEX idx_seat_inventory_holder ON seat_inventory (session_id, holder_id);
