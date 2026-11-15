-- 시나리오 종료 후 정합성. 위반 수(violations)는 전부 0이어야 한다. 마지막 두 표는 참고용 분포다.
SELECT 'order가 종결 상태가 아님' AS check_name, COUNT(*) AS violations
  FROM reservation_db.orders WHERE status NOT IN ('CONFIRMED', 'FAILED', 'REFUNDED', 'EXPIRED')
UNION ALL
SELECT 'payment가 종결 상태가 아님(REQUESTED, UNKNOWN, CANCELLING)', COUNT(*)
  FROM payment_db.payment WHERE status IN ('REQUESTED', 'UNKNOWN', 'CANCELLING')
UNION ALL
SELECT '결제 승인인데 주문이 CONFIRMED가 아님(돈만 나감)', COUNT(*)
  FROM payment_db.payment p JOIN reservation_db.orders o ON o.order_id = p.order_id
  WHERE p.status = 'APPROVED' AND o.status <> 'CONFIRMED'
UNION ALL
SELECT 'CONFIRMED인데 결제가 승인된 적 없음', COUNT(*)
  FROM reservation_db.orders o LEFT JOIN payment_db.payment p ON p.order_id = o.order_id
  WHERE o.status = 'CONFIRMED' AND (p.order_id IS NULL OR p.status IN ('REQUESTED', 'UNKNOWN', 'FAILED'))
UNION ALL
SELECT 'REFUNDED인데 결제가 CANCELLED가 아님', COUNT(*)
  FROM reservation_db.orders o LEFT JOIN payment_db.payment p ON p.order_id = o.order_id
  WHERE o.status = 'REFUNDED' AND (p.order_id IS NULL OR p.status <> 'CANCELLED')
UNION ALL
SELECT 'FAILED인데 결제가 승인·취소 흐름에 있음', COUNT(*)
  FROM reservation_db.orders o JOIN payment_db.payment p ON p.order_id = o.order_id
  WHERE o.status = 'FAILED' AND p.status IN ('APPROVED', 'CANCELLING', 'CANCELLED', 'CANCEL_FAILED')
UNION ALL
SELECT 'CONFIRMED인데 예매 행이 없음', COUNT(*)
  FROM reservation_db.orders o LEFT JOIN reservation_db.reservation r ON r.order_id = o.order_id
  WHERE o.status = 'CONFIRMED' AND r.order_id IS NULL
UNION ALL
SELECT 'CONFIRMED가 아닌 주문에 예매 행이 있음', COUNT(*)
  FROM reservation_db.reservation r JOIN reservation_db.orders o ON o.order_id = r.order_id
  WHERE o.status <> 'CONFIRMED'
UNION ALL
SELECT 'SOLD 좌석 수와 유효 예매 좌석 수가 다름', ABS(
    (SELECT COUNT(*) FROM reservation_db.seat_inventory WHERE status = 'SOLD')
  - (SELECT COALESCE(SUM(JSON_LENGTH(seat_ids)), 0) FROM reservation_db.reservation WHERE status IN ('CONFIRMED', 'CANCEL_REQUESTED')))
UNION ALL
SELECT 'SOLD 좌석의 hold_id에 CONFIRMED 주문이 없음', COUNT(*)
  FROM reservation_db.seat_inventory s LEFT JOIN reservation_db.orders o ON o.hold_id = s.hold_id AND o.status = 'CONFIRMED'
  WHERE s.status = 'SOLD' AND o.order_id IS NULL
UNION ALL
SELECT '같은 주문에 같은 결과 이벤트가 둘 이상(이중 확정·이중 환불)', COUNT(*) FROM (
    SELECT message_key, event_type FROM reservation_db.outbox
    WHERE event_type IN ('ReservationConfirmed', 'OrderRefunded', 'ReservationCancelled')
    GROUP BY message_key, event_type HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'hold_id 하나를 둘 이상이 가짐', COUNT(*) FROM (
    SELECT hold_id FROM reservation_db.seat_inventory WHERE hold_id IS NOT NULL
    GROUP BY hold_id HAVING COUNT(DISTINCT holder_id) > 1) t
UNION ALL
SELECT '1인 한도(4매) 초과', COUNT(*) FROM (
    SELECT session_id, holder_id FROM reservation_db.seat_inventory WHERE status IN ('HELD', 'SOLD')
    GROUP BY session_id, holder_id HAVING COUNT(*) > 4) t;

SELECT 'orders' AS tbl, status, COUNT(*) AS cnt FROM reservation_db.orders GROUP BY status
UNION ALL SELECT 'payment', status, COUNT(*) FROM payment_db.payment GROUP BY status
UNION ALL SELECT 'reservation', status, COUNT(*) FROM reservation_db.reservation GROUP BY status
UNION ALL SELECT 'seat', status, COUNT(*) FROM reservation_db.seat_inventory WHERE status <> 'AVAILABLE' GROUP BY status
ORDER BY 1, 2;
