-- 측정용 공연장 배치: 100열 x 100석 = 10,000석. 좌석 ID는 1~10000(열 r, 번호 n → (r - 1) * 100 + n).
-- 공연장 하나(FC000680-09)에 hall_layout을 먼저 넣어 두면 공연 등록 때 배치를 새로 만들지 않고 이 배치를 쓴다.
-- event_db가 비어 있을 때(compose down -v 직후) 한 번 실행한다.
SET SESSION cte_max_recursion_depth = 20000;

DELETE FROM hall_seat WHERE mt13id = 'FC000680-09';
DELETE FROM hall_layout WHERE mt13id = 'FC000680-09';

INSERT INTO hall_layout (mt13id, seat_count, generated_at) VALUES ('FC000680-09', 10000, NOW(6));

INSERT INTO hall_seat (seat_id, mt13id, floor, section, row_label, seat_no, x, y, depth, wheelchair)
WITH RECURSIVE n (i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < 10000)
SELECT i, 'FC000680-09', 1, 'A', CONCAT('R', FLOOR((i - 1) / 100) + 1), ((i - 1) MOD 100) + 1,
       ((i - 1) MOD 100) + 1, FLOOR((i - 1) / 100) + 1, FLOOR((i - 1) / 10), FALSE
FROM n;
