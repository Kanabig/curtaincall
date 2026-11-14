-- 거래 조회 중 404 외의 응답(오류, 예상 밖 상태)을 한 번이라도 받았는지. 조회가 내내 404였던 건만 망취소 대상이다
ALTER TABLE payment ADD COLUMN inquiry_error_seen BOOLEAN NOT NULL DEFAULT FALSE;
