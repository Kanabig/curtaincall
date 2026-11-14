-- 공연 목록 조회(EVT-4). 목록은 판매 등록된 공연만 시작일순으로 읽고, 필터 컬럼마다 시작일을 뒤에 붙여 정렬까지 인덱스로 처리한다.
CREATE INDEX idx_performance_start ON performance (start_date);
CREATE INDEX idx_performance_genre_start ON performance (genre, start_date);
CREATE INDEX idx_performance_area_start ON performance (area, start_date);
CREATE INDEX idx_performance_state_start ON performance (state, start_date);
CREATE INDEX idx_performance_end ON performance (end_date);

-- maxPrice 필터와 상세의 판매 회차 목록은 공연별로 시작 시각 이후의 회차를 찾는다.
CREATE INDEX idx_session_performance_start ON session (performance_id, start_at);
DROP INDEX idx_session_performance ON session;
