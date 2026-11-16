-- FAILED·EXPIRED로 끝난 주문의 사유. 성공·진행 중·환불 주문은 NULL이다.
ALTER TABLE orders ADD COLUMN failure_code VARCHAR(30) NULL AFTER payment_id;
