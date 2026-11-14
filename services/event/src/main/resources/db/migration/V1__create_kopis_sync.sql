-- 공연시설(mt10id)과 공연장(mt13id). 좌석 배치 생성의 기준이 되는 seat_scale은 KOPIS가 비워 두거나 0으로 주는 경우가 있어 NULL을 허용한다.
CREATE TABLE venue (
    mt10id       VARCHAR(20)   NOT NULL PRIMARY KEY,
    name         VARCHAR(200)  NOT NULL,
    hall_count   INT           NULL,
    seat_scale   INT           NULL,
    address      VARCHAR(300)  NULL,
    latitude     DECIMAL(10,7) NULL,
    longitude    DECIMAL(10,7) NULL,
    source       VARCHAR(20)   NOT NULL DEFAULT 'KOPIS',
    fetched_at   DATETIME(6)   NOT NULL
);

CREATE TABLE hall (
    mt13id              VARCHAR(30)  NOT NULL PRIMARY KEY,
    mt10id              VARCHAR(20)  NOT NULL,
    name                VARCHAR(200) NOT NULL,
    seat_scale          INT          NULL,
    disabled_seat_scale INT          NULL,
    INDEX idx_hall_venue (mt10id)
);

-- 공연. 목록 응답으로 만들고 상세 응답으로 채운다. detail_status가 PENDING인 행이 상세 조회 대기열이다.
-- price_guidance, date_guidance는 KOPIS 원문이다. 파싱은 등급·회차 생성에서 하고 실패해도 원문은 남는다.
CREATE TABLE performance (
    mt20id            VARCHAR(20)  NOT NULL PRIMARY KEY,
    name              VARCHAR(300) NOT NULL,
    mt10id            VARCHAR(20)  NULL,
    mt13id            VARCHAR(30)  NULL,
    facility_name     VARCHAR(200) NULL,
    genre             VARCHAR(50)  NULL,
    area              VARCHAR(50)  NULL,
    state             VARCHAR(20)  NULL,
    start_date        DATE         NOT NULL,
    end_date          DATE         NOT NULL,
    open_run          BOOLEAN      NOT NULL DEFAULT FALSE,
    poster            VARCHAR(500) NULL,
    runtime           VARCHAR(50)  NULL,
    age               VARCHAR(50)  NULL,
    price_guidance    TEXT         NULL,
    date_guidance     TEXT         NULL,
    relates           TEXT         NULL,
    kopis_updated_at  DATETIME(6)  NULL,
    detail_status     VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    detail_attempts   INT          NOT NULL DEFAULT 0,
    detail_fetched_at DATETIME(6)  NULL,
    source            VARCHAR(20)  NOT NULL DEFAULT 'KOPIS',
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    INDEX idx_performance_detail (detail_status, start_date),
    INDEX idx_performance_venue (mt10id)
);

-- KOPIS 하루 호출 수. 정기 동기화와 수동 동기화가 같은 장부를 쓴다.
CREATE TABLE kopis_call_log (
    call_date DATE NOT NULL PRIMARY KEY,
    count     INT  NOT NULL DEFAULT 0
);

-- 동기화 실행 이력. 실제 KOPIS를 호출한(metered) 실행 중 list_completed인 마지막 실행의 시작일이 다음 증분 조회의 기준일이다.
-- synced_until은 그 실행이 목록을 받은 기간의 끝날이다. 창이 매일 밀리므로 그 뒤 구간은 다음 실행이 afterdate 없이 전체로 받는다.
-- fixture 실행은 기준일로 쓰지 않는다. 나중에 키를 넣고 live로 바꿨을 때 첫 동기화가 전체 적재여야 하기 때문이다.
CREATE TABLE kopis_sync_run (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trigger_type      VARCHAR(10)  NOT NULL,
    metered           BOOLEAN      NOT NULL,
    status            VARCHAR(10)  NOT NULL,
    started_at        DATETIME(6)  NOT NULL,
    finished_at       DATETIME(6)  NULL,
    after_date        DATE         NULL,
    synced_until      DATE         NULL,
    list_completed    BOOLEAN      NOT NULL DEFAULT FALSE,
    calls             INT          NOT NULL DEFAULT 0,
    listed            INT          NOT NULL DEFAULT 0,
    details_fetched   INT          NOT NULL DEFAULT 0,
    facilities_fetched INT         NOT NULL DEFAULT 0,
    parse_failures    INT          NOT NULL DEFAULT 0,
    error             VARCHAR(300) NULL,
    INDEX idx_kopis_sync_run_started (started_at)
);

-- 인스턴스가 여럿이어도 동기화가 한 번에 하나만 돈다. locked_until은 임대 만료 시각이라 프로세스가 죽어도 풀린다.
CREATE TABLE kopis_sync_lock (
    name         VARCHAR(30) NOT NULL PRIMARY KEY,
    owner        VARCHAR(60) NULL,
    locked_until DATETIME(6) NOT NULL
);
