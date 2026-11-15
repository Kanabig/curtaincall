-- 종료 후 정합성(주문 단계 포함). @SID는 run.sh가 회차 ID로 바꾼다. 위반 수가 전부 0이어야 한다.
-- perf/seat-hold/verify.sql은 seat_inventory만 보므로, 확정 예매와 좌석 재고의 대응은 여기서 따로 본다.
SELECT '확정 예매 두 건 이상에 들어간 좌석' AS check_name, COUNT(*) AS violations FROM (
    SELECT j.seat_id FROM reservation r,
        JSON_TABLE(r.seat_ids, '$[*]' COLUMNS (seat_id BIGINT PATH '$')) j
    WHERE r.session_id = @SID AND r.status = 'CONFIRMED'
    GROUP BY j.seat_id HAVING COUNT(*) > 1) t
UNION ALL
SELECT 'SOLD 좌석 수와 확정 예매 좌석 수의 차이', ABS(
    (SELECT COUNT(*) FROM seat_inventory WHERE session_id = @SID AND status = 'SOLD')
  - (SELECT COALESCE(SUM(JSON_LENGTH(seat_ids)), 0) FROM reservation WHERE session_id = @SID AND status = 'CONFIRMED'))
UNION ALL
SELECT '확정 예매에 없는 SOLD 좌석', COUNT(*) FROM seat_inventory i
    WHERE i.session_id = @SID AND i.status = 'SOLD' AND NOT EXISTS (
        SELECT 1 FROM reservation r, JSON_TABLE(r.seat_ids, '$[*]' COLUMNS (seat_id BIGINT PATH '$')) j
        WHERE r.session_id = @SID AND r.status = 'CONFIRMED' AND j.seat_id = i.seat_id)
UNION ALL
SELECT 'CONFIRMED 주문 좌석 수와 확정 예매 좌석 수의 차이', ABS(
    (SELECT COALESCE(SUM(JSON_LENGTH(seat_ids)), 0) FROM orders WHERE session_id = @SID AND status = 'CONFIRMED')
  - (SELECT COALESCE(SUM(JSON_LENGTH(seat_ids)), 0) FROM reservation WHERE session_id = @SID AND status = 'CONFIRMED'));
