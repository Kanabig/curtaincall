-- 시설 상세 파싱이 계속 실패하는 시설. venue 행이 없으면 매 실행이 같은 시설을 다시 받아 호출 한도(하루 100회)를 쓰므로,
-- 실패 횟수를 세어 한도에 닿으면 facility-refresh가 지날 때까지 건너뛴다.
CREATE TABLE venue_fetch_failure (
    mt10id         VARCHAR(20) NOT NULL PRIMARY KEY,
    attempts       INT         NOT NULL,
    last_failed_at DATETIME(6) NOT NULL
);
