#!/bin/bash
# 좌석 상태 조회 캐시 전후 측정. 사용법:
#   perf/seat-status/run.sh <캐시 TTL: 0s|2s> [프로젝트 이름] [폴링 VU 수] [지속 시간] [점유 요청 초당 수(0이면 폴링만)]
# 1만 석 회차(perf/seat-hold/seed.sql)에 폴링을 걸고, 같은 시간의 DB SELECT 수와 응답 시간을 남긴다.
# 점유 부하를 함께 주면 폴링이 점유 p95에 주는 영향을 볼 수 있다. 결과는 perf/seat-status/results/ 에 남는다(커밋하지 않는다).
set -euo pipefail

TTL="${1:?0s 또는 2s}"
PROJECT="${2:-bench}"
VUS="${3:-300}"
DURATION="${4:-60s}"
HOLD_RATE="${5:-0}"
K6_IMAGE="grafana/k6:2.4.0"

cd "$(dirname "$0")/../.."
OUT="perf/seat-status/results/ttl-${TTL}-hold-${HOLD_RATE}"
mkdir -p "$OUT"
compose() { docker compose -p "$PROJECT" "$@"; }
mysql_root() { compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" reservation_db 2>/dev/null'; }
k6run() { docker run --rm --user "$(id -u):$(id -g)" --network "${PROJECT}_default" -v "$PWD/perf:/perf:ro" "$@"; }

CURTAINCALL_SEAT_STATUS_CACHE_TTL="$TTL" compose up -d --build --force-recreate --wait reservation
mysql_root < perf/seat-hold/seed.sql
# 워밍업
k6run -e VUS=50 -e DURATION=10s "$K6_IMAGE" run --quiet /perf/seat-status/seat-status.js > /dev/null || true
mysql_root < perf/seat-hold/seed.sql

{
    echo "commit:   $(git rev-parse HEAD)"
    echo "ttl=$TTL vus=$VUS duration=$DURATION hold_rate=$HOLD_RATE"
    echo "host:     $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}')"
    echo "load avg: $(cut -d' ' -f1-3 /proc/loadavg)"
    echo "containers:"; docker ps --format '  {{.Names}}'
} | tee "$OUT/env.txt"

echo "TRUNCATE performance_schema.events_statements_summary_by_digest;" | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" 2>/dev/null'
( while sleep 5; do docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' | grep -E "${PROJECT}-(reservation|mysql)"; echo '--'; done > "$OUT/stats-load.txt" ) &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT

if [ "$HOLD_RATE" != "0" ]; then
    k6run -e RATE="$HOLD_RATE" -e DURATION="$DURATION" "$K6_IMAGE" run --quiet /perf/seat-hold/seat-hold.js > "$OUT/k6-hold.txt" 2>&1 &
    HOLD_PID=$!
fi
k6run -e VUS="$VUS" -e DURATION="$DURATION" "$K6_IMAGE" run /perf/seat-status/seat-status.js | tee "$OUT/k6-poll.txt" || true
[ -z "${HOLD_PID:-}" ] || wait "$HOLD_PID" || true
kill $SAMPLER 2>/dev/null || true

echo "--- 좌석 상태 SELECT 실행 수 (DB)" | tee "$OUT/db-queries.txt"
echo "SELECT count_star, ROUND(avg_timer_wait/1e9,2) AS avg_ms FROM performance_schema.events_statements_summary_by_digest WHERE schema_name='reservation_db' AND digest_text LIKE 'SELECT \`seat_id\` , STATUS FROM \`seat_inventory\` WHERE \`session_id\` = ? AND%'" \
    | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t 2>/dev/null' | tee -a "$OUT/db-queries.txt"
if [ "$HOLD_RATE" != "0" ]; then grep -E "latency|hold_" "$OUT/k6-hold.txt" | tee "$OUT/hold-summary.txt"; fi
