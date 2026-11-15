-- 수렴 검증용 초기화. 시나리오 사이에 reservation_db와 payment_db를 비우고 회차 7에 좌석 2,000석을 채운다.
-- root 계정으로 실행한다(두 DB를 함께 건드린다).
SET SESSION cte_max_recursion_depth = 5000;

DELETE FROM reservation_db.reservation;
DELETE FROM reservation_db.orders;
DELETE FROM reservation_db.seat_inventory;
DELETE FROM reservation_db.user_session_quota;
DELETE FROM reservation_db.session_stock;
DELETE FROM reservation_db.idempotency_key;
DELETE FROM reservation_db.outbox;
DELETE FROM reservation_db.processed_event;
DELETE FROM payment_db.payment;
DELETE FROM payment_db.outbox;
DELETE FROM payment_db.processed_event;

INSERT INTO reservation_db.session_stock (session_id, status, seat_count, next_attempt_at, ready_at)
VALUES (7, 'READY', 2000, NOW(6), NOW(6));

INSERT INTO reservation_db.seat_inventory (session_id, seat_id, grade, price)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 2000)
SELECT 7, i, 'R', 110000 FROM n;
