#!/bin/bash
# 좌석 배치도 캐시 미스 동시 요청 측정. 사용법: perf/open-sale/seat-map-stampede.sh [프로젝트 이름] [동시 요청 수]
# 떠 있는 스택(run.sh로 회차를 만든 뒤)에서 event를 재시작해 캐시를 비우고, VUS명이 동시에 배치도를 요청한다.
# 결과: k6 응답 시간, DB 배치도 조회 실행 수(single-flight가 없으면 동시 요청 수에 가깝다).
set -euo pipefail
PROJECT="${1:-bench}"; VUS="${2:-500}"; REPEAT="${REPEAT:-3}"
cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
export IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short=7 HEAD)}"
OUT="perf/open-sale/results/seatmap-$(date +%m%d-%H%M%S)"; mkdir -p "$OUT"
compose() { docker compose -p "$PROJECT" -f compose.yml -f perf/open-sale/compose.perf.yml "$@"; }
SESSION_ID="$(echo "SELECT MAX(session_id) FROM session_stock" | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N reservation_db 2>/dev/null' | tr -d '[:space:]')"
echo "commit=$(git rev-parse HEAD) image_tag=$IMAGE_TAG vus=$VUS session=$SESSION_ID" | tee "$OUT/env.txt"
for i in $(seq 1 "$REPEAT"); do
    compose restart event > /dev/null
    until [ "$(docker inspect -f '{{.State.Health.Status}}' "${PROJECT}-event-1")" = "healthy" ]; do sleep 2; done
    echo "TRUNCATE performance_schema.events_statements_summary_by_digest;" | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null'
    docker run --rm --network "${PROJECT}_default" -v "$PWD/perf/open-sale:/scripts:ro" \
        -e VUS="$VUS" -e SESSION_ID="$SESSION_ID" grafana/k6:2.4.0 run --quiet /scripts/seat-map-stampede.js 2>&1 \
        | grep -E "seatmap_cold_ms|http_req_failed" | tee "$OUT/k6-$i.txt"
    echo "SELECT count_star AS executions, ROUND(avg_timer_wait/1e9,1) AS avg_ms FROM performance_schema.events_statements_summary_by_digest WHERE schema_name='event_db' AND digest_text LIKE '%hall_seat%' AND digest_text LIKE 'SELECT%'" \
        | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t 2>/dev/null' | tee "$OUT/db-$i.txt"
done
