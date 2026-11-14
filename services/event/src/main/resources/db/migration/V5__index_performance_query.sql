-- 공연 목록(EVT-4)은 판매 등록된 공연(sale_performance, 수백 건 규모)을 uk 인덱스로 읽고 performance는 PK로 붙인다.
-- 필터는 붙인 뒤 걸고 정렬은 그 결과에서 하므로 performance의 필터 컬럼 인덱스는 쓰이지 않아 두지 않는다.

-- maxPrice 필터와 상세의 판매 회차 목록은 공연별로 시작 시각 이후의 회차를 찾는다.
CREATE INDEX idx_session_performance_start ON session (performance_id, start_at);
DROP INDEX idx_session_performance ON session;
