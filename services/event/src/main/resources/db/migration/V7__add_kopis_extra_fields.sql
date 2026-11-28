-- 공연 상세·시설 상세에서 추가로 저장하는 값. Y/N 항목은 응답에 없으면 NULL이다.
-- extras_fetched가 FALSE면 이 값들이 생기기 전에 받은 행이다. 판매 등록된 공연과 그 시설은 다음 동기화가 한 번 다시 받는다.
ALTER TABLE performance
    ADD COLUMN cast_names       TEXT    NULL,
    ADD COLUMN producer         TEXT    NULL,
    ADD COLUMN intro_images     TEXT    NULL,
    ADD COLUMN is_child         BOOLEAN NULL,
    ADD COLUMN is_visit         BOOLEAN NULL,
    ADD COLUMN is_license       BOOLEAN NULL,
    ADD COLUMN is_creative      BOOLEAN NULL,
    ADD COLUMN is_daehakro      BOOLEAN NULL,
    ADD COLUMN is_festival      BOOLEAN NULL,
    ADD COLUMN extras_fetched   BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE venue
    ADD COLUMN phone             TEXT    NULL,
    ADD COLUMN website           TEXT    NULL,
    ADD COLUMN parking_lot       BOOLEAN NULL,
    ADD COLUMN barrier_elevator  BOOLEAN NULL,
    ADD COLUMN barrier_parking   BOOLEAN NULL,
    ADD COLUMN barrier_restroom  BOOLEAN NULL,
    ADD COLUMN barrier_ramp      BOOLEAN NULL,
    ADD COLUMN cafe              BOOLEAN NULL,
    ADD COLUMN restaurant        BOOLEAN NULL,
    ADD COLUMN convenience_store BOOLEAN NULL,
    ADD COLUMN playroom          BOOLEAN NULL,
    ADD COLUMN nursing_room      BOOLEAN NULL,
    ADD COLUMN extras_fetched    BOOLEAN NOT NULL DEFAULT FALSE;
