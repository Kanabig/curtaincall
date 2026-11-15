#!/bin/bash
# 판매 오픈 부하 측정. 사용법:
#   perf/open-sale/run.sh flow [프로젝트 이름]            1만 명 진입부터 결제까지(MODE=flow)
#   perf/open-sale/run.sh nfr7 [프로젝트 이름]            예매 API 응답 시간, 폴링 유무 비교(MODE=nfr7, POLLERS로 폴링 수)
# 환경변수(기본값): USERS=10000 ADMISSION=50 MAX_ACTIVE=300 OPEN_DELAY=120(오픈까지 초)
#   nfr7: BOOK_RATE=50 DURATION=60s POLLERS=0
#   FRESH=1 이면 볼륨까지 지우고 처음부터 띄운다(기본). 0이면 떠 있는 스택에 회차만 새로 만든다.
# 측정 전에 다른 세션의 Docker 테스트가 멈춰 있어야 하고, 측정은 flock /tmp/curtaincall-bench.lock 아래에서 돌린다.
# 결과는 perf/open-sale/results/<모드>-<시각>/ 에 남는다(커밋하지 않는다).
set -euo pipefail

MODE="${1:?flow 또는 nfr7}"
PROJECT="${2:-bench}"
USERS="${USERS:-10000}"
ADMISSION="${ADMISSION:-50}"
MAX_ACTIVE="${MAX_ACTIVE:-300}"
OPEN_DELAY="${OPEN_DELAY:-120}"
FRESH="${FRESH:-1}"
POLLERS="${POLLERS:-0}"
K6_IMAGE="grafana/k6:2.4.0"

cd "$(dirname "$0")/../.."
set -a; . ./.env; set +a
# 이미지를 커밋 SHA로 태그해 다른 프로젝트의 빌드에 덮어써지지 않게 한다. 작업 트리가 더럽거나 추적하지 않는 소스가 있으면 SHA와 이미지가 어긋나므로 멈춘다
if [ -n "$(git status --porcelain --untracked-files=no)" ] && [ "${ALLOW_DIRTY:-0}" != "1" ]; then
    echo "작업 트리에 커밋하지 않은 변경이 있다. 커밋하거나 ALLOW_DIRTY=1로 실행한다" >&2; exit 1
fi
export IMAGE_TAG="$(git rev-parse --short=7 HEAD)"
OUT="perf/open-sale/results/${MODE}-$(date +%m%d-%H%M%S)${TAG:+-$TAG}"
mkdir -p "$OUT"
compose() { docker compose -p "$PROJECT" -f compose.yml -f perf/open-sale/compose.perf.yml "$@"; }
mysql_root() { compose exec -T mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $* 2>/dev/null"; }
inside() { docker run --rm --network "${PROJECT}_default" curlimages/curl:8.11.1 -s "$@"; }

if [ "$FRESH" = "1" ]; then
    compose down -v --remove-orphans > /dev/null 2>&1 || true
    compose up -d --build --wait
    mysql_root -N event_db < perf/open-sale/seed-event.sql
else
    compose up -d --wait
fi

GW_PORT="$(compose port gateway 8080 | sed 's/.*://')"
GW="http://localhost:${GW_PORT}"
login="$(curl -fs -X POST "$GW/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}")"
ADMIN_TOKEN="$(echo "$login" | sed 's/.*"accessToken":"\([^"]*\)".*/\1/')"
admin() { curl -fs -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' "$@"; }

# 공연 등록(fixture)과 회차 생성. 오픈 시각은 지금부터 OPEN_DELAY초 뒤
admin -X POST "$GW/api/v1/admin/performances" -d '{"kopisId":"PF305718"}' > "$OUT/performance.json" || true
PERF_ID="$(admin "$GW/api/v1/performances?size=1" | sed 's/.*"id":\([0-9]*\).*/\1/')"
OPEN_AT_MS=$(( ($(date +%s) + OPEN_DELAY) * 1000 ))
OPEN_AT_ISO="$(date -d "@$((OPEN_AT_MS / 1000))" +%Y-%m-%dT%H:%M:%S%:z)"
START_AT_ISO="$(date -d "@$((OPEN_AT_MS / 1000 + 30 * 86400))" +%Y-%m-%dT%H:%M:%S%:z)"
admin -X POST "$GW/api/v1/admin/performances/${PERF_ID}/sessions" \
    -d "{\"startAt\":\"${START_AT_ISO}\",\"saleOpenAt\":\"${OPEN_AT_ISO}\",\"prices\":[{\"grade\":\"전석\",\"price\":10000}],\"admissionPerSecond\":${ADMISSION},\"maxActiveUsers\":${MAX_ACTIVE}}" \
    | tee "$OUT/session.json"
echo
SESSION_ID="$(sed 's/.*"sessionId":\([0-9]*\).*/\1/' "$OUT/session.json")"

# reservation이 재고 10,000석을 만들 때까지 기다린다
for _ in $(seq 1 120); do
    READY="$(echo "SELECT COUNT(*) FROM session_stock WHERE session_id=${SESSION_ID} AND status='READY'" | mysql_root -N reservation_db | tr -d '[:space:]')"
    [ "$READY" = "1" ] && break
    sleep 2
done
[ "$READY" = "1" ] || { echo "재고 준비 실패" >&2; exit 1; }
SEAT_BASE="$(echo "SELECT MIN(seat_id) FROM seat_inventory WHERE session_id=${SESSION_ID}" | mysql_root -N reservation_db | tr -d '[:space:]')"

scrape() { # <서비스> <관리 포트> <파일>
    compose exec -T "$1" curl -fs "http://localhost:$2/actuator/prometheus" > "$3" 2>/dev/null || true
}
snapshot() { # <접미사>
    scrape gateway 8089 "$OUT/prom-gateway-$1.txt"; scrape queue 8083 "$OUT/prom-queue-$1.txt"
    scrape reservation 8084 "$OUT/prom-reservation-$1.txt"; scrape payment 8085 "$OUT/prom-payment-$1.txt"
    compose exec -T redis redis-cli INFO commandstats > "$OUT/redis-commandstats-$1.txt" 2>/dev/null || true
    compose exec -T redis redis-cli INFO stats > "$OUT/redis-stats-$1.txt" 2>/dev/null || true
}

{
    echo "commit:     $(git rev-parse HEAD)  image_tag=$IMAGE_TAG"
    echo "mode=$MODE project=$PROJECT users=$USERS admissionPerSecond=$ADMISSION maxActiveUsers=$MAX_ACTIVE pollers=$POLLERS"
    echo "seat_status_ttl=${CURTAINCALL_SEAT_STATUS_CACHE_TTL:-2s} hold_attempts=${MAX_HOLD_ATTEMPTS:-3} spread_ms=${SPREAD_MS:-1000} pg_delay_ms=${PG_DELAY_MS:-0} ip_limit=${RATE_LIMIT_IP_PER_SECOND:-off} entry_only=${ENTRY_ONLY:-0} direct_queue=${DIRECT_QUEUE:-0}"
    echo "session=$SESSION_ID seat_base=$SEAT_BASE open_at=$OPEN_AT_ISO"
    echo "host:       $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}'), $(uname -sr)"
    echo "load avg:   $(cut -d' ' -f1-3 /proc/loadavg)"
    echo "k6:         $K6_IMAGE"
    echo "containers:"; docker ps --format '  {{.Names}}  {{.Image}}'
    echo "top cpu processes:"; ps -eo pcpu,comm --sort=-pcpu | head -8 | sed 's/^/  /'
} | tee "$OUT/env.txt"

if [ -n "${PG_DELAY_MS:-}" ]; then
    # 가짜 PG 승인 지연 주입(payment Bulkhead 거절 비율을 보려는 실행)
    inside -X PUT http://mock-pg:8090/pg/admin/faults -H 'Content-Type: application/json' \
        -d "{\"delayMs\":${PG_DELAY_MS},\"failureRate\":0,\"timeoutRate\":0,\"dropResponseRate\":0}" | tee "$OUT/pg-faults.txt"; echo
fi
snapshot before
docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}' > "$OUT/stats-idle.txt"
( while sleep 5; do date +%T; docker stats --no-stream --format '{{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}'; echo '--'; done > "$OUT/stats-load.txt" ) &
SAMPLER=$!
( while sleep 5; do cut -d' ' -f1-3 /proc/loadavg; done > "$OUT/loadavg.txt" ) &
LOADAVG=$!
# 소비 lag(Kafka consumer lag 합)와 outbox 미발행 행 수의 피크를 보려고 5초마다 남긴다
( while sleep 5; do
    for svc in queue:8083 reservation:8084 payment:8085; do
        lag=$(compose exec -T "${svc%%:*}" curl -fs "http://localhost:${svc##*:}/actuator/prometheus" 2>/dev/null \
            | awk '/^kafka_consumer_fetch_manager_records_lag\{/{s+=$NF} END{print s+0}')
        echo "$(date +%T) ${svc%%:*} consumer_lag=$lag"
    done
    gauge=$(compose exec -T reservation curl -fs http://localhost:8084/actuator/prometheus 2>/dev/null | awk '/^outbox_unpublished[{ ]/{s+=$NF} END{print s+0}')
    dlt=$(compose exec -T reservation curl -fs http://localhost:8084/actuator/prometheus 2>/dev/null | awk '/^outbox_consumer_dlt/ && !/^#/{s+=$NF} END{print s+0}')
    echo "$(date +%T) seats $(echo "SELECT CONCAT('held=', SUM(status='HELD'), ' sold=', SUM(status='SOLD')) FROM seat_inventory WHERE session_id=${SESSION_ID}" | mysql_root -N reservation_db | tr -d '\r')"
    echo "$(date +%T) queue active=$(compose exec -T redis redis-cli ZCARD "q:${SESSION_ID}:active" | tr -d '[:space:]') waiting=$(compose exec -T redis redis-cli ZCARD "q:${SESSION_ID}:waiting" | tr -d '[:space:]')"
    echo "$(date +%T) reservation outbox_unpublished_db=$(echo 'SELECT COUNT(*) FROM outbox WHERE published_at IS NULL' | mysql_root -N reservation_db | tr -d '[:space:]') gauge=$gauge dlt=$dlt"
  done > "$OUT/lag.txt" ) &
LAGSAMPLER=$!
trap 'kill $SAMPLER $LOADAVG $LAGSAMPLER 2>/dev/null || true' EXIT

innodb() { mysql_root -N -e "\"SELECT NAME, \\\`COUNT\\\` FROM information_schema.INNODB_METRICS WHERE NAME IN ('lock_deadlocks','lock_timeouts','trx_rseg_history_len') ORDER BY NAME\""; }
innodb > "$OUT/innodb-before.txt" || true

docker run --rm --user "$(id -u):$(id -g)" --network "${PROJECT}_default" \
    -v "$PWD/perf/open-sale:/scripts:ro" -v "$PWD/$OUT:/out" \
    -e MODE="$MODE" -e BASE_URL=http://gateway:8080 -e SESSION_ID="$SESSION_ID" -e OPEN_AT_MS="$OPEN_AT_MS" \
    -e ACCESS_SECRET="$JWT_ACCESS_SECRET" -e ENTRY_SECRET="$JWT_ENTRY_SECRET" -e SEAT_BASE="$SEAT_BASE" \
    -e USERS="$USERS" -e POLLERS="$POLLERS" -e BOOK_RATE="${BOOK_RATE:-50}" -e DURATION="${DURATION:-60s}" \
    -e SPREAD_MS="${SPREAD_MS:-1000}" -e MAX_HOLD_ATTEMPTS="${MAX_HOLD_ATTEMPTS:-3}" -e ENTRY_ONLY="${ENTRY_ONLY:-0}" -e DIRECT_QUEUE="${DIRECT_QUEUE:-0}" -e THINK_MS="${THINK_MS:-1500}" -e MAX_WAIT_S="${MAX_WAIT_S:-900}" \
    "$K6_IMAGE" run /scripts/open-sale.js | tee "$OUT/k6.txt" || true

kill $SAMPLER $LOADAVG $LAGSAMPLER 2>/dev/null || true
snapshot after
innodb > "$OUT/innodb-after.txt" || true
compose logs --no-color --since 30m queue gateway reservation payment 2>&1 | grep -E "WARN|ERROR" | sort | uniq -c | sort -rn | head -30 > "$OUT/log-warn-error.txt" || true

echo "--- 종료 후 정합성 (위반 수)"
sed "s/session_id = 1/session_id = ${SESSION_ID}/g; s/session_id=1/session_id=${SESSION_ID}/g" perf/seat-hold/verify.sql \
    | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t reservation_db 2>/dev/null' | tee "$OUT/verify.txt"
echo "SELECT status, COUNT(*) AS n FROM seat_inventory WHERE session_id=${SESSION_ID} GROUP BY status;
SELECT status, COUNT(*) AS n FROM orders GROUP BY status;" | compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t reservation_db 2>/dev/null' | tee "$OUT/final-state.txt"
echo "결과: $OUT"
