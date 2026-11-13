#!/bin/bash
# 서비스마다 스키마 하나와 그 스키마 권한만 가진 계정을 만든다.
# 다른 스키마를 조회하면 권한 단계에서 거부된다.
set -euo pipefail

create_schema() {
    local schema="$1" user="$2" password="$3"
    mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`${schema}\`.* TO '${user}'@'%';
SQL
}

create_schema user_db user_svc "${USER_DB_PASSWORD}"
create_schema event_db event_svc "${EVENT_DB_PASSWORD}"
create_schema reservation_db reservation_svc "${RESERVATION_DB_PASSWORD}"
create_schema payment_db payment_svc "${PAYMENT_DB_PASSWORD}"
create_schema notification_db notification_svc "${NOTIFICATION_DB_PASSWORD}"
