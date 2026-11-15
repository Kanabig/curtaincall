CREATE TABLE users (
    user_id       BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(60)  NOT NULL,
    name          VARCHAR(50)  NOT NULL,
    role          VARCHAR(10)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    -- 이메일은 소문자로 정규화해 저장한다. 동시 가입은 이 제약이 막는다(USR-1)
    CONSTRAINT uk_users_email UNIQUE (email)
);
