#!/bin/bash
# 장애 격리 시나리오 공용 함수. run.sh가 source 한다. PROJECT, OUT 변수가 있어야 한다.
# 전제: .env(.env.example 복사) 안에 ADMIN_EMAIL, ADMIN_PASSWORD, 프로젝트별 호스트 포트.

COMPOSE_FILES=(-f compose.yml)
compose() { docker compose -p "$PROJECT" "${COMPOSE_FILES[@]}" "$@"; }
GW() { echo "http://localhost:${GATEWAY_HOST_PORT:-$(grep -s '^GATEWAY_HOST_PORT=' .env | cut -d= -f2)}"; }
gw="$(GW)"; gw="${gw%:}"; [ "$gw" = "http://localhost:" ] && gw="http://localhost:8080"

say() { printf '\n== %s\n' "$*"; }
ok() { printf 'OK   %s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; FAILED=1; }
check() { # check "설명" 기대값 실제값
    if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: 기대 $2, 실제 $3"; fi
}

api() { # api METHOD PATH [token] [json] [extra header...]  → 본문 출력, 상태 코드는 $CODE
    local method="$1" path="$2" token="${3:-}" body="${4:-}"; shift 4 2>/dev/null || shift $#
    local args=(-s -o /tmp/fault-body.$$ -w '%{http_code}' -X "$method" "$gw$path" -H 'Content-Type: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-d "$body")
    for h in "$@"; do args+=(-H "$h"); done
    CODE=$(curl "${args[@]}" || echo 000)
    cat /tmp/fault-body.$$ 2>/dev/null
}

signup_login() { # signup_login 이메일 → access token
    api POST /api/v1/users "" "{\"email\":\"$1\",\"password\":\"password-1234\",\"name\":\"fault\"}" > /dev/null
    api POST /api/v1/auth/login "" "{\"email\":\"$1\",\"password\":\"password-1234\"}" | jq -r .accessToken
}

admin_login() {
    local email password
    email=$(grep -s '^ADMIN_EMAIL=' .env | cut -d= -f2); password=$(grep -s '^ADMIN_PASSWORD=' .env | cut -d= -f2)
    api POST /api/v1/auth/login "" "{\"email\":\"$email\",\"password\":\"$password\"}" | jq -r .accessToken
}

# 판매 오픈이 $1초 뒤인 회차를 만들고 ID를 출력한다. fixture 공연(PF305735)을 등록한다
make_session() {
    local open_in="${1:-20}" admin perf start open
    admin=$(admin_login)
    perf=$(api POST /api/v1/admin/performances "$admin" '{"kopisId":"PF305735"}' | jq -r '.performanceId // .id')
    open=$(date -d "+${open_in} seconds" --iso-8601=seconds)
    start=$(date -d "+3 days" --iso-8601=seconds)
    api POST "/api/v1/admin/performances/$perf/sessions" "$admin" \
        "{\"startAt\":\"$start\",\"saleOpenAt\":\"$open\",\"prices\":[{\"grade\":\"R\",\"price\":10000}],\"admissionPerSecond\":50,\"maxActiveUsers\":300}" \
        | jq -r '.sessionId // .id'
}

wait_open() { sleep "$(( ${1:-20} + 2 ))"; }

enter_token() { # enter_token 회차 토큰 → 입장 토큰(입장할 때까지 폴링, 최대 60초)
    api POST "/api/v1/queue/sessions/$1/entries" "$2" "" > /dev/null
    for _ in $(seq 1 60); do
        local res; res=$(api GET "/api/v1/queue/sessions/$1/entries/me" "$2" "")
        [ "$(echo "$res" | jq -r .status)" = ADMITTED ] && { echo "$res" | jq -r .entryToken; return; }
        sleep 1
    done
}

purchase() { # purchase 회차 access token 입장토큰 좌석ID → 주문 ID (결제 요청까지)
    local hold order
    hold=$(api POST "/api/v1/sessions/$1/holds" "$2" "{\"seatIds\":[$4]}" "X-Entry-Token: $3" | jq -r .holdId)
    [ "$CODE" = 201 ] || { echo "hold:$CODE"; return 1; }
    order=$(api POST /api/v1/orders "$2" "{\"holdId\":\"$hold\"}" "X-Entry-Token: $3" "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" | jq -r .orderId)
    [ "$CODE" = 201 ] || { echo "order:$CODE"; return 1; }
    api POST "/api/v1/orders/$order/payment" "$2" '{"method":"CARD","cardToken":"tok_fault"}' "X-Entry-Token: $3" "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" > /dev/null
    [ "$CODE" = 202 ] || { echo "payment:$CODE"; return 1; }
    echo "$order"
}

order_status() { api GET "/api/v1/orders/$1" "$2" "" | jq -r '[.status, .paymentStatus] | join("/")'; }

wait_order() { # wait_order 주문 토큰 기대상태(정규식) [초]
    local s=""
    for _ in $(seq 1 "${4:-60}"); do
        s=$(order_status "$1" "$2")
        [[ "$s" =~ $3 ]] && { echo "$s"; return 0; }
        sleep 1
    done
    echo "$s"; return 1
}

latency_stats() { # latency_stats 횟수 METHOD PATH 토큰 → "status별 개수, p50/p95/max(ms)"
    local n="$1"; shift
    for _ in $(seq 1 "$n"); do
        curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -X "$1" "$gw$2" -H "Authorization: Bearer $3" -H 'Content-Type: application/json'
    done | awk '{c[$1]++; t[NR]=$2*1000} END {n=asort(t); for (k in c) printf "status %s: %d  ", k, c[k]; printf "\np50=%.0fms p95=%.0fms max=%.0fms\n", t[int(n*0.5)], t[int(n*0.95)], t[n]}'
}

scenario_redis() {
    FAILED=0
    say "준비: 회차 생성, 사용자 두 명"
    compose up -d --build --wait
    local sid a b ta tb
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-a-$$@example.com"); b=$(signup_login "fault-b-$$@example.com")
    ta=$(enter_token "$sid" "$a")
    [ -n "$ta" ] && ok "A 입장 토큰 확보" || bad "A 입장 실패"

    say "Redis 중단"
    compose stop redis
    say "새 진입(B): 503이어야 하고 서킷 덕에 빨라야 한다 (중단 직후 첫 요청 이후)"
    latency_stats 30 POST "/api/v1/queue/sessions/$sid/entries" "$b" | tee "$OUT/enter-during-outage.txt"
    say "queue 컨테이너 상태: liveness는 UP, 컨테이너 healthy여야 한다"
    check "queue liveness" 200 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health/liveness)"
    check "queue health(Redis 포함)" 503 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health)"
    say "토큰 보유자(A): 점유·주문·결제가 계속돼야 한다"
    local order; order=$(purchase "$sid" "$a" "$ta" 1) && ok "A 결제 요청까지 완료: $order" || bad "A 예매 실패: $order"
    check "A 주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" 'CONFIRMED|PAID|APPROVED' 60 || true)"
    say "속도 제한 fail-open: 예매 API가 429·5xx 없이 응답해야 한다"
    latency_stats 30 GET "/api/v1/sessions/$sid/seat-status" "$a" | tee "$OUT/seat-status-during-outage.txt"
    compose exec -T gateway curl -s http://localhost:8089/actuator/prometheus | grep 'gateway_ratelimit_failopen' | tee "$OUT/failopen-metric.txt" || true

    say "Redis 복구"
    compose start redis
    for _ in $(seq 1 30); do [ "$(compose ps redis --format '{{.Health}}')" = healthy ] && break; sleep 1; done
    sleep 3
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check "복구 뒤 B 진입 성공(데이터 유지, 200/201)" "ok" "$([[ $CODE =~ ^20[01]$ ]] && echo ok || echo "$CODE")"

    say "Redis 데이터 유실 후 복구: event.session 재소비"
    compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --describe --entity-type topics --entity-name event.session | tee "$OUT/topic-config.txt"
    grep -q 'cleanup.policy=compact' "$OUT/topic-config.txt" && ok "event.session compaction" || bad "event.session이 compaction이 아니다"
    compose exec -T redis redis-cli FLUSHALL
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check "유실 직후 진입은 회차 없음 404" 404 "$CODE"
    compose stop queue
    compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group queue --topic event.session --reset-offsets --to-earliest --execute
    compose start queue
    sleep 10
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check "오프셋 되감기 뒤 진입 복구(200/201)" "ok" "$([[ $CODE =~ ^20[01]$ ]] && echo ok || echo "$CODE")"
    exit_status
}

scenario_pg() {
    FAILED=0
    compose up -d --build --wait
    local sid a ta
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-pg-$$@example.com"); ta=$(enter_token "$sid" "$a")
    local pg="http://localhost:${MOCK_PG_HOST_PORT:-$(grep -s '^MOCK_PG_HOST_PORT=' .env | cut -d= -f2)}"

    say "PG 지연 8초(읽기 타임아웃 5초 초과): 결제는 UNKNOWN으로 가도 다른 API는 정상이어야 한다"
    curl -s -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":8000,"failureRate":0,"timeoutRate":0,"dropResponseRate":0}'; echo
    local orders=() i
    for i in 1 2 3 4 5; do orders+=("$(purchase "$sid" "$a" "$ta" "$i")"); done
    say "결제가 걸려 있는 동안 다른 API 응답"
    latency_stats 30 GET "/api/v1/sessions/$sid/seat-status" "$a" | tee "$OUT/seat-status-during-delay.txt"
    latency_stats 30 GET /api/v1/performances "$a" | tee "$OUT/performances-during-delay.txt"
    compose exec -T payment curl -s http://localhost:8085/actuator/prometheus | grep -E 'tomcat_threads_busy|resilience4j_bulkhead_available|resilience4j_circuitbreaker_state' | tee "$OUT/payment-metrics-during-delay.txt" || true
    say "서킷을 열어 본다: 실패율 100%"
    curl -s -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":0,"failureRate":1.0,"timeoutRate":0,"dropResponseRate":0}'; echo
    for i in 6 7 8 9; do orders+=("$(purchase "$sid" "$a" "$ta" "$i" || true)"); done
    sleep 5
    compose exec -T payment curl -s http://localhost:8085/actuator/prometheus | grep -E 'resilience4j_circuitbreaker_state.*open|resilience4j_circuitbreaker_calls' | tee "$OUT/payment-circuit.txt" || true
    for o in "${orders[@]}"; do echo "$o: $(order_status "$o" "$a" 2>/dev/null || true)"; done | tee "$OUT/orders-after-fault.txt"
    say "복구"
    curl -s -X DELETE "$pg/pg/admin/faults"; echo
    sleep 15
    for o in "${orders[@]}"; do echo "$o: $(order_status "$o" "$a" 2>/dev/null || true)"; done | tee "$OUT/orders-after-recovery.txt"
    echo "수렴 확인: UNKNOWN이던 주문이 조회로 APPROVED/FAILED로 정리됐는지 위 목록을 본다(10분까지 걸릴 수 있다)."
    exit_status
}

scenario_aux() {
    FAILED=0
    compose up -d --build --wait
    local sid a ta
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-aux-$$@example.com"); ta=$(enter_token "$sid" "$a")
    say "notification, search-assist 중단"
    compose stop notification
    local order; order=$(purchase "$sid" "$a" "$ta" 1) && ok "결제 요청까지 완료: $order" || bad "예매 실패: $order"
    check "주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" 'CONFIRMED|PAID|APPROVED' 60 || true)"
    echo "search-assist: 해당 없음(미구현). 구현 뒤 중단 시나리오를 추가한다"
    say "notification 복구: 쌓인 이벤트로 메일이 오는지(mailpit)"
    compose start notification
    sleep 15
    curl -s "http://localhost:${MAILPIT_UI_HOST_PORT:-$(grep -s '^MAILPIT_UI_HOST_PORT=' .env | cut -d= -f2)}/api/v1/messages" | jq '.messages_count' | tee "$OUT/mails.txt"
    exit_status
}

scenario_kopis() {
    FAILED=0
    COMPOSE_FILES=(-f compose.yml -f perf/fault/kopis-override.yml)
    compose up -d --build --wait event kopis-stub
    local admin sid a ta stub
    admin=$(admin_login)
    stub() { compose exec -T kopis-stub python -c "import urllib.request,sys;print(urllib.request.urlopen('http://localhost:9000/__mode/$1').read().decode())"; }
    run_sync() { api POST /api/v1/admin/kopis/sync "$admin" "" > /dev/null; sleep "${1:-8}"
        compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N event_db -e "SELECT status, calls, parse_failures, error FROM kopis_sync_run ORDER BY id DESC LIMIT 1" 2>/dev/null'; }
    stub ok; say "정상 동기화로 기준 데이터 적재"; run_sync 10
    local before; before=$(compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N event_db -e "SELECT COUNT(*) FROM performance" 2>/dev/null')
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-kopis-$$@example.com")
    for mode in 5xx broken timeout; do
        say "KOPIS $mode"; stub "$mode"
        run_sync 15 | tee -a "$OUT/sync-runs.txt"
        check "공연 수 유지($mode)" "$before" "$(compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N event_db -e "SELECT COUNT(*) FROM performance" 2>/dev/null')"
        check "공연 조회 200($mode)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$gw/api/v1/performances")"
        ta=$(enter_token "$sid" "$a"); [ -n "$ta" ] && ok "대기열·입장 정상($mode)" || bad "입장 실패($mode)"
        purchase "$sid" "$a" "$ta" "$RANDOM" > /dev/null && ok "점유·주문·결제 요청 정상($mode)" || bad "예매 경로 실패($mode)"
    done
    stub ok
    exit_status
}

scenario_dlt() {
    FAILED=0
    compose up -d --build --wait
    local kt=/opt/kafka/bin
    say "event.session에 깨진 메시지를 넣는다(queue·reservation이 각자 {토픽}.{서비스}.DLT로 보내야 한다)"
    echo '999:{not-json' | compose exec -T kafka $kt/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic event.session --property parse.key=true --property key.separator=:
    sleep 12
    for t in event.session.queue.DLT event.session.reservation.DLT; do
        compose exec -T kafka $kt/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$t" --from-beginning --timeout-ms 8000 --property print.headers=true 2>/dev/null | tee "$OUT/$t.txt"
        [ -s "$OUT/$t.txt" ] && ok "$t 에 쌓였다" || bad "$t 가 비어 있다"
    done
    echo "소비자별 DLT 이름과 헤더(어느 서비스가 보냈는지)를 위 출력에서 확인한다."
    exit_status
}

exit_status() { if [ "${FAILED:-0}" = 0 ]; then say "모두 통과"; else say "실패 있음"; return 1; fi; }
