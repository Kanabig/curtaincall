CREATE TABLE sample_seat (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(20) NOT NULL,
    status VARCHAR(10) NOT NULL
);

INSERT INTO sample_seat (label, status) VALUES ('A-1', 'AVAILABLE'), ('A-2', 'HELD');
