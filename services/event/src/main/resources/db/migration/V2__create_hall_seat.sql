-- 공연장 좌석 배치 템플릿. 공연장(mt13id)마다 한 번 만들고 바꾸지 않는다. 회차별 판매 상태는 reservation이 가진다.
-- hall_layout 행이 있으면 배치가 만들어진 것이다. 공연장 객석 수가 나중에 바뀌어도 이미 만든 배치는 그대로 둔다.
CREATE TABLE hall_layout (
    mt13id       VARCHAR(30) NOT NULL PRIMARY KEY,
    seat_count   INT         NOT NULL,
    generated_at DATETIME(6) NOT NULL
);

-- x, y는 격자 좌표(무대 쪽이 y=1). depth는 무대에서 먼 정도로 0(맨 앞)~999(맨 뒤)이고, 등급은 회차 등록 때 가격표와 depth로 정한다.
CREATE TABLE hall_seat (
    seat_id    BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    mt13id     VARCHAR(30) NOT NULL,
    floor      TINYINT     NOT NULL,
    section    VARCHAR(10) NOT NULL,
    row_label  VARCHAR(10) NOT NULL,
    seat_no    SMALLINT    NOT NULL,
    x          INT         NOT NULL,
    y          INT         NOT NULL,
    depth      SMALLINT    NOT NULL,
    wheelchair BOOLEAN     NOT NULL DEFAULT FALSE,
    UNIQUE KEY uk_hall_seat_position (mt13id, floor, row_label, seat_no),
    UNIQUE KEY uk_hall_seat_xy (mt13id, x, y)
);
