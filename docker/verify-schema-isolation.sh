#!/bin/bash
# DB 계정 권한 검증 (.env의 비밀번호 사용)
#   - app, flyway 계정 모두 자기 스키마는 조회되고 다른 스키마는 거부된다(ERROR 1044)
#   - app 계정은 DML만: DDL 거부(ERROR 1142)
#   - flyway 계정은 DDL + DML 가능
# 사용: docker/verify-schema-isolation.sh [compose 프로젝트 이름]
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
project_args=()
[ $# -ge 1 ] && project_args=(-p "$1")

services=(user event reservation payment notification)
fail=0

password() { # 서비스, 종류(app|flyway)
    local var
    var=$(echo "$1_$2_PASSWORD" | tr '[:lower:]' '[:upper:]')
    echo "${!var}"
}

sql() { # 서비스, 종류, SQL → 출력(에러 포함), 종료 코드
    docker compose "${project_args[@]}" exec -T mysql \
        mysql -u"$1_$2" -p"$(password "$1" "$2")" -D "$1_db" -e "$3" 2>&1
}

sql_global() { # 서비스, 종류, SQL (기본 스키마 없이 실행)
    docker compose "${project_args[@]}" exec -T mysql \
        mysql -u"$1_$2" -p"$(password "$1" "$2")" -e "$3" 2>&1
}

expect_ok() { # 설명, 출력, 종료 코드
    if [ "$3" -eq 0 ]; then echo "ok   $1"; else echo "FAIL $1: $2"; fail=1; fi
}

expect_error() { # 설명, 출력, 기대 에러 번호
    if echo "$2" | grep -q "ERROR $3"; then echo "ok   $1 (거부 $3)"; else echo "FAIL $1 (거부되지 않음): $2"; fail=1; fi
}

for svc in "${services[@]}"; do
    # 스키마 격리
    for kind in app flyway; do
        for target in "${services[@]}"; do
            out=$(sql_global "$svc" "$kind" "SHOW TABLES FROM \`${target}_db\`"); code=$?
            if [ "$svc" = "$target" ]; then
                expect_ok "${svc}_${kind} -> ${target}_db 조회" "$out" $code
            else
                expect_error "${svc}_${kind} -> ${target}_db 조회" "$out" 1044
            fi
        done
    done

    # 권한 분리: flyway가 만든 테이블에 app은 DML만 가능
    out=$(sql "$svc" flyway "CREATE TABLE _perm_check (id INT PRIMARY KEY, v INT)"); expect_ok "${svc}_flyway CREATE TABLE" "$out" $?
    out=$(sql "$svc" app "INSERT INTO _perm_check VALUES (1, 1); UPDATE _perm_check SET v = 2; SELECT * FROM _perm_check; DELETE FROM _perm_check"); expect_ok "${svc}_app 새 테이블 DML" "$out" $?
    out=$(sql "$svc" app "CREATE TABLE _perm_denied (id INT)"); expect_error "${svc}_app CREATE TABLE" "$out" 1142
    out=$(sql "$svc" app "ALTER TABLE _perm_check ADD COLUMN x INT"); expect_error "${svc}_app ALTER TABLE" "$out" 1142
    out=$(sql "$svc" app "DROP TABLE _perm_check"); expect_error "${svc}_app DROP TABLE" "$out" 1142
    out=$(sql "$svc" flyway "ALTER TABLE _perm_check ADD COLUMN x INT"); expect_ok "${svc}_flyway ALTER TABLE" "$out" $?
    out=$(sql "$svc" flyway "DROP TABLE _perm_check"); expect_ok "${svc}_flyway DROP TABLE" "$out" $?
done
exit $fail
