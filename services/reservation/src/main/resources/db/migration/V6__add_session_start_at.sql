-- 회차 시작 시각. 예매 취소 기한(공연 전날 23:59:59 KST) 계산에 쓴다. SessionCreated·SessionUpdated로 채운다.
ALTER TABLE session_stock ADD COLUMN start_at DATETIME(6) NULL;
