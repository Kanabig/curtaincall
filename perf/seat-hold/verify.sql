-- 측정 종료 후 정합성. 첫 열이 위반 수이고 전부 0이어야 한다.
SELECT 'hold_id 하나를 둘 이상이 가짐' AS check_name, COUNT(*) AS violations FROM (
    SELECT hold_id FROM seat_inventory WHERE hold_id IS NOT NULL
    GROUP BY hold_id HAVING COUNT(DISTINCT holder_id) > 1) t
UNION ALL
SELECT '1인 한도(4매) 초과', COUNT(*) FROM (
    SELECT session_id, holder_id FROM seat_inventory WHERE status IN ('HELD', 'SOLD')
    GROUP BY session_id, holder_id HAVING COUNT(*) > 4) t
UNION ALL
SELECT 'AVAILABLE인데 점유 정보가 남음', COUNT(*) FROM seat_inventory
    WHERE status = 'AVAILABLE' AND (hold_id IS NOT NULL OR holder_id IS NOT NULL)
UNION ALL
SELECT '점유·판매 수가 좌석 수를 넘음', COUNT(*) FROM (
    SELECT s.session_id FROM session_stock s JOIN seat_inventory i USING (session_id)
    WHERE i.status IN ('HELD', 'SOLD') GROUP BY s.session_id, s.seat_count
    HAVING COUNT(*) > s.seat_count) t;
