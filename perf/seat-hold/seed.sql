-- 측정용 재고: 회차 1, 100열 x 100석 = 10,000석. 앞 10열(1~1000번)은 VIP.
-- reservation_db를 비우고 다시 채운다. 측정 직전에 실행한다.
SET SESSION cte_max_recursion_depth = 20000;

DELETE FROM seat_inventory;
DELETE FROM user_session_quota;
DELETE FROM session_stock;

INSERT INTO session_stock (session_id, status, seat_count, next_attempt_at, ready_at)
VALUES (1, 'READY', 10000, NOW(6), NOW(6));

INSERT INTO seat_inventory (session_id, seat_id, grade, price)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 10000)
SELECT 1, i, IF(i <= 1000, 'VIP', 'R'), IF(i <= 1000, 150000, 110000) FROM n;

ANALYZE TABLE seat_inventory;
