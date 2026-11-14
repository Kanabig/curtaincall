-- 회차 가격 수정(SessionUpdated)을 재고에 반영하는 표시. requested_at은 받은 이벤트 중 가장 늦은 occurredAt이고
-- (순서가 뒤집혀 와도 줄지 않는다), synced_at은 그 시각까지 반영한 값이다. requested_at이 더 크면 반영할 것이 남은 회차다.
ALTER TABLE session_stock
    ADD COLUMN price_requested_at DATETIME(6) NULL,
    ADD COLUMN price_synced_at    DATETIME(6) NULL;
