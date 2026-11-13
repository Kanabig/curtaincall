#!/bin/bash
# 서비스 계정이 자기 스키마만 조회하고 다른 스키마는 권한 단계에서 거부되는지 확인한다.
# 사용: docker/verify-schema-isolation.sh [compose 프로젝트 이름]  (.env의 비밀번호를 쓴다)
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
project_args=()
[ $# -ge 1 ] && project_args=(-p "$1")

declare -A pw=(
    [user]="$USER_DB_PASSWORD" [event]="$EVENT_DB_PASSWORD" [reservation]="$RESERVATION_DB_PASSWORD"
    [payment]="$PAYMENT_DB_PASSWORD" [notification]="$NOTIFICATION_DB_PASSWORD"
)
services=(user event reservation payment notification)
fail=0

query() { # 계정 서비스, 대상 스키마
    docker compose "${project_args[@]}" exec -T mysql \
        mysql -u"$1_svc" -p"${pw[$1]}" -e "SHOW TABLES FROM \`$2_db\`" 2>&1
}

for from in "${services[@]}"; do
    for to in "${services[@]}"; do
        out=$(query "$from" "$to"); code=$?
        if [ "$from" = "$to" ]; then
            [ $code -eq 0 ] || { echo "FAIL ${from}_svc -> ${to}_db 자기 스키마 조회 실패: $out"; fail=1; }
        elif echo "$out" | grep -q "ERROR 1044"; then
            echo "ok   ${from}_svc -> ${to}_db 거부됨 (1044)"
        else
            echo "FAIL ${from}_svc -> ${to}_db 거부되지 않음: $out"; fail=1
        fi
    done
done
exit $fail
