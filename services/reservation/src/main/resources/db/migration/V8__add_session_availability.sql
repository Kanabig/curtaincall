-- 회차 매진 상태(QUE-8). sold_out은 마지막으로 발행한 판정, availability_version은 SessionAvailabilityChanged의 회차별 version이다.
-- 판정을 바꿀 때 session_stock 행을 잠근 채 version을 1씩 올리고 outbox에 쓰므로, 인스턴스가 여럿이어도 같은 회차의 이벤트 순서가 직렬화된다.
ALTER TABLE session_stock
    ADD COLUMN sold_out             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN availability_version BIGINT  NOT NULL DEFAULT 0;
