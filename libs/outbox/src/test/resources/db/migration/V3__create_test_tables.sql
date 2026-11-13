CREATE TABLE test_order (
    id     VARCHAR(36) NOT NULL PRIMARY KEY,
    status VARCHAR(20) NOT NULL
);

CREATE TABLE test_effect (
    id       BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id CHAR(36)    NOT NULL,
    note     VARCHAR(50) NULL
);
