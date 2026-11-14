#!/bin/bash
# 좌석 점유 전략 비교 측정. 사용법:
#   perf/seat-hold/run.sh <conditional-update|pessimistic-lock> [프로젝트 이름] [초당 요청 수] [지속 시간]
# compose 프로젝트에 mysql과 reservation이 있어야 한다. 전략을 바꿔 reservation을 다시 만들고,
# 재고를 새로 채운 뒤 k6를 돌려 InnoDB 지표와 종료 후 정합성을 함께 출력한다.
# 결과는 perf/seat-hold/results/<전략>/ 에 남는다(커밋하지 않는다).
set -euo pipefail

STRATEGY="${1:?conditional-update 또는 pessimistic-lock}"
PROJECT="${2:-bench}"
RATE="${3:-300}"
DURATION="${4:-60s}"
K6_IMAGE="grafana/k6:2.4.0"

cd "$(dirname "$0")/../.."
OUT="perf/seat-hold/results/${STRATEGY}"
mkdir -p "$OUT"
compose() { docker compose -p "$PROJECT" "$@"; }
mysql_root() { compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N reservation_db 2>/dev/null'; }
metrics() {
    compose exec -T mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N -e \"SELECT NAME, \\\`COUNT\\\` FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks','lock_timeouts') ORDER BY NAME\" 2>/dev/null"
}

CURTAINCALL_HOLD_STRATEGY="$STRATEGY" compose up -d --force-recreate --wait reservation
mysql_root < perf/seat-hold/seed.sql

{
    echo "commit:     $(git rev-parse HEAD)"
    echo "strategy:   $STRATEGY  rate=$RATE/s  duration=$DURATION"
    echo "host:       $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}'), $(uname -sr)"
    echo "k6:         $K6_IMAGE   mysql: $(compose exec -T mysql mysqld --version | sed 's/.*Ver //')"
    echo "applied:    $(compose exec -T reservation printenv CURTAINCALL_HOLD_STRATEGY)"
    echo "load avg:   $(cut -d' ' -f1-3 /proc/loadavg)"
    echo "containers:"; docker ps --format '  {{.Names}}  {{.Image}}'
    echo "top cpu processes:"; ps -eo pcpu,comm --sort=-pcpu | head -6 | sed 's/^/  /'
} | tee "$OUT/env.txt"

metrics > "$OUT/innodb-before.txt"
docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' > "$OUT/stats-idle.txt"
( while sleep 5; do docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}'; echo '--'; done > "$OUT/stats-load.txt" ) &
SAMPLER=$!
trap 'kill $SAMPLER 2>/dev/null || true' EXIT

docker run --rm --user "$(id -u):$(id -g)" --network "${PROJECT}_default" -v "$PWD/perf/seat-hold:/scripts:ro" -v "$PWD/$OUT:/out" \
    -e RATE="$RATE" -e DURATION="$DURATION" "$K6_IMAGE" run /scripts/seat-hold.js | tee "$OUT/k6.txt" || true

kill $SAMPLER 2>/dev/null || true
metrics > "$OUT/innodb-after.txt"
echo "--- InnoDB (전/후)"; paste "$OUT/innodb-before.txt" "$OUT/innodb-after.txt"
echo "--- 종료 후 정합성 (위반 수)"; compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t reservation_db 2>/dev/null' < perf/seat-hold/verify.sql | tee "$OUT/verify.txt"
HELD=$(echo "SELECT COUNT(*) FROM seat_inventory WHERE status = 'HELD'" | mysql_root | tr -d '[:space:]')
K6_SEATS=$(sed -n 's/.*hold_ok .*seats \([0-9]*\).*/\1/p' "$OUT/k6.txt" | head -1)
echo "--- 점유 좌석 수: DB HELD=$HELD, k6 응답 합계=$K6_SEATS" | tee "$OUT/held-count.txt"
[ "$HELD" = "$K6_SEATS" ] || { echo "불일치: 응답과 DB의 점유 좌석 수가 다르다" | tee -a "$OUT/held-count.txt"; exit 1; }
