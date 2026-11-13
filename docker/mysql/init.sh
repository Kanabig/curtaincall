#!/bin/bash
# 서비스마다 스키마 하나와 계정 둘을 만든다.
#   <서비스>_app    자기 스키마의 DML만. 앱 런타임이 쓴다.
#   <서비스>_flyway 자기 스키마의 DDL + DML. Flyway 마이그레이션만 쓴다.
# 권한은 스키마 단위(`schema`.*)라 나중에 생기는 테이블에도 적용된다.
# 다른 스키마를 조회하면 권한 단계에서 거부된다.
set -euo pipefail

create_schema() {
    local service="$1" app_password="$2" flyway_password="$3"
    local schema="${service}_db"
    mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${schema}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${service}_app'@'%' IDENTIFIED BY '${app_password}';
CREATE USER IF NOT EXISTS '${service}_flyway'@'%' IDENTIFIED BY '${flyway_password}';
GRANT SELECT, INSERT, UPDATE, DELETE ON \`${schema}\`.* TO '${service}_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, DROP, INDEX, REFERENCES ON \`${schema}\`.* TO '${service}_flyway'@'%';
SQL
}

create_schema user "${USER_APP_PASSWORD}" "${USER_FLYWAY_PASSWORD}"
create_schema event "${EVENT_APP_PASSWORD}" "${EVENT_FLYWAY_PASSWORD}"
create_schema reservation "${RESERVATION_APP_PASSWORD}" "${RESERVATION_FLYWAY_PASSWORD}"
create_schema payment "${PAYMENT_APP_PASSWORD}" "${PAYMENT_FLYWAY_PASSWORD}"
create_schema notification "${NOTIFICATION_APP_PASSWORD}" "${NOTIFICATION_FLYWAY_PASSWORD}"
