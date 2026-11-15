-- 종료 후 정합성(주문 단계 포함). @SID는 run.sh가 회차 ID로 바꾼다. 위반 수가 전부 0이어야 한다.
-- perf/seat-hold/verify.sql은 seat_inventory만 보므로, 확정 주문과 좌석 재고의 대응은 여기서 따로 본다.
-- 이 시나리오에는 사용자 취소가 없다. 취소가 섞이면 환불 중인 주문의 좌석이 SOLD로 남아 3번이 오탐할 수 있다.
-- 초과 판매는 점유가 만료된 뒤 다른 사람이 가져간 좌석을 이전 주문이 확정할 때 생기므로 주문 쪽에서 센다.
SELECT '같은 좌석이 CONFIRMED 주문 둘 이상에 들어 있음' AS check_name, COUNT(*) AS violations FROM (
    SELECT j.seat_id FROM orders o,
        JSON_TABLE(o.seat_ids, '$[*]' COLUMNS (seat_id BIGINT PATH '$')) j
    WHERE o.session_id = @SID AND o.status = 'CONFIRMED'
    GROUP BY j.seat_id HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'CONFIRMED 주문 좌석 합과 SOLD 좌석 수의 차이', ABS(
    (SELECT COALESCE(SUM(JSON_LENGTH(seat_ids)), 0) FROM orders WHERE session_id = @SID AND status = 'CONFIRMED')
  - (SELECT COUNT(*) FROM seat_inventory WHERE session_id = @SID AND status = 'SOLD'))
UNION ALL
SELECT 'CONFIRMED 주문이 정확히 하나가 아닌 SOLD 좌석', COUNT(*) FROM seat_inventory i
    WHERE i.session_id = @SID AND i.status = 'SOLD' AND (
        SELECT COUNT(*) FROM orders o, JSON_TABLE(o.seat_ids, '$[*]' COLUMNS (seat_id BIGINT PATH '$')) j
        WHERE o.session_id = @SID AND o.status = 'CONFIRMED' AND j.seat_id = i.seat_id) <> 1
UNION ALL
SELECT '사용자·회차별 CONFIRMED 좌석 4매 초과', COUNT(*) FROM (
    SELECT user_id FROM orders WHERE session_id = @SID AND status = 'CONFIRMED'
    GROUP BY user_id HAVING SUM(JSON_LENGTH(seat_ids)) > 4) t;
