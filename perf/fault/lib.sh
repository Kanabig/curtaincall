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
check() { # check "설명" 기대값 실제값. 실제값이 비어 있으면 실패다(명령이 조용히 실패한 경우를 거른다)
    if [ -z "$3" ]; then bad "$1: 결과가 비었다(기대 $2)"
    elif [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: 기대 $2, 실제 $3"; fi
}
check_re() { # check_re "설명" 정규식 실제값
    if [ -z "$3" ]; then bad "$1: 결과가 비었다"
    elif [[ "$3" =~ $2 ]]; then ok "$1 ($3)"; else bad "$1: 기대 /$2/, 실제 $3"; fi
}
check_nonempty() { # check_nonempty "설명" 파일. 증거 파일이 비면 실패다
    if [ -s "$2" ]; then ok "$1 ($(wc -l < "$2")줄)"; else bad "$1: $2 가 비었다"; fi
}
note() { printf 'NOTE %s\n' "$*"; }  # 판정에 넣지 않는 관찰값(기준을 확인하지 못한 항목)

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


probe() { # probe 횟수 METHOD PATH 토큰 파일 → 파일에 "상태코드 초"를 한 줄씩 남긴다
    local n="$1" f="$5" code
    : > "$f"
    for _ in $(seq 1 "$n"); do
        code=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 10 -X "$2" "$gw$3" \
            -H "Authorization: Bearer $4" -H 'Content-Type: application/json') || code="000 10"
        echo "$code" >> "$f"
    done
}

assert_probe() { # assert_probe 설명 파일 허용코드정규식 p95상한ms 기대횟수
    local label="$1" f="$2" re="$3" max="$4" want="$5" n wrong p95
    n=$(wc -l < "$f")
    [ "$n" -ge "$want" ] || { bad "$label: 측정이 $n회뿐이다(기대 $want)"; return; }
    wrong=$(awk -v re="$re" '$1 !~ re {c++} END {print c+0}' "$f")
    p95=$(awk '{print int($2 * 1000)}' "$f" | sort -n | awk '{a[NR] = $1} END {i = int(NR * 0.95); if (i < 1) i = 1; print a[i]}')
    awk '{c[$1]++} END {for (k in c) printf "%s×%d ", k, c[k]; print ""}' "$f" > "$f.codes"
    if [ "$wrong" -gt 0 ]; then bad "$label: 허용 밖 상태 코드 ${wrong}건 ($(cat "$f.codes"))"
    elif [ "$p95" -gt "$max" ]; then bad "$label: p95 ${p95}ms > ${max}ms ($(cat "$f.codes"))"
    else ok "$label: p95 ${p95}ms <= ${max}ms ($(cat "$f.codes"))"; fi
}

sql_event() { compose exec -T mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N event_db -e \"$1\" 2>/dev/null" | tr -d '\r'; }

metric() { # metric 서비스 포트 정규식 → 일치하는 줄(없으면 빈 문자열)
    compose exec -T "$1" curl -s "http://localhost:$2/actuator/prometheus" | grep -E "$3" || true
}

# 시나리오가 끝나기 전에 죽어도 중단한 서비스와 PG 장애를 되돌린다
restore_all() {
    compose start redis notification > /dev/null 2>&1 || true
    local pg; pg="http://localhost:${MOCK_PG_HOST_PORT:-$(grep -s '^MOCK_PG_HOST_PORT=' .env | cut -d= -f2)}"
    curl -s -X DELETE "$pg/pg/admin/faults" > /dev/null 2>&1 || true
    compose exec -T kopis-stub python -c "import urllib.request;urllib.request.urlopen('http://localhost:9000/__mode/ok')" > /dev/null 2>&1 || true
}

# 판정은 파일로 남긴다. run.sh는 이 파일이 PASS가 아니면(없는 경우 포함) 실패로 끝낸다
exit_status() {
    if [ "${FAILED:-0}" = 0 ]; then echo PASS > "$OUT/verdict"; say "PASS"; else echo FAIL > "$OUT/verdict"; say "FAIL"; return 1; fi
}

# ---- 시나리오. 순서: dlt → aux → redis → pg → kopis. 기대와 판정 기준은 각 시나리오 머리말에 있다 ----

# 기대: event.session 불량 메시지가 소비자별 DLT(.queue.DLT, .reservation.DLT)에 쌓이고 DLT 발행 실패 로그가 없다.
# 기존 버그(reservation에 event.session DLT 미선언)는 토픽 목록·발행 실패 로그에 남는다. 수정 전 커밋에서 같은 스크립트를 돌리면 FAIL이어야 한다.
scenario_dlt() {
    FAILED=0
    compose up -d --build --wait
    local kt=/opt/kafka/bin t
    say "증거: 주입 전 토픽 목록"
    compose exec -T kafka $kt/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort > "$OUT/topics-before.txt"
    check_nonempty "토픽 목록 수집" "$OUT/topics-before.txt"
    say "event.session에 깨진 메시지를 넣는다"
    echo '999:{not-json' | compose exec -T kafka $kt/kafka-console-producer.sh --bootstrap-server localhost:9092 \
        --topic event.session --property parse.key=true --property key.separator=:
    sleep 15
    compose exec -T kafka $kt/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort > "$OUT/topics-after.txt"
    for t in event.session.queue.DLT event.session.reservation.DLT; do
        grep -qx "$t" "$OUT/topics-after.txt" && ok "토픽 존재: $t" || bad "토픽 없음: $t (토픽 목록은 topics-after.txt)"
        # 소비 시간 초과로 종료 코드가 0이 아니어도 판정은 파일 내용으로 한다
        compose exec -T kafka $kt/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$t" \
            --from-beginning --timeout-ms 8000 --property print.headers=true > "$OUT/$t.txt" 2>/dev/null || true
        check_nonempty "$t 에 불량 메시지가 쌓였다" "$OUT/$t.txt"
    done
    say "증거: DLT 발행 실패 로그(없어야 한다)"
    for svc in queue reservation; do
        compose logs --no-log-prefix "$svc" 2>&1 \
            | grep -iE '(dead.?letter|DLT).*(fail|error|exception|unable|could not|timeout)|(fail|error|exception|unable|could not).*(dead.?letter|DLT)' \
            | tail -50 > "$OUT/$svc-dlt-failures.txt" || true
        check "$svc DLT 발행 실패 로그 줄 수" 0 "$(wc -l < "$OUT/$svc-dlt-failures.txt")"
    done
    say "reservation /actuator/dlt"
    compose exec -T reservation curl -s http://localhost:8084/actuator/dlt > "$OUT/reservation-dlt-topics.txt" || true
    grep -q 'event.session.reservation.DLT' "$OUT/reservation-dlt-topics.txt" && ok "actuator/dlt 목록에 있다" || bad "actuator/dlt 목록에 event.session.reservation.DLT가 없다"
    compose exec -T reservation curl -s "http://localhost:8084/actuator/dlt/event.session.reservation.DLT?limit=5" > "$OUT/reservation-dlt-entries.txt" || true
    check_nonempty "reservation DLT 항목 조회" "$OUT/reservation-dlt-entries.txt"
    exit_status
}

# 기대: notification을 멈춰도 예매(점유→주문→결제)가 CONFIRMED로 끝나고, 복구 뒤 쌓인 이벤트로 메일이 나간다.
# search-assist는 미구현이라 해당 없음(구현 뒤 중단 시나리오 추가).
scenario_aux() {
    FAILED=0
    compose up -d --build --wait
    local sid a ta order mails
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-aux-$$@example.com"); ta=$(enter_token "$sid" "$a")
    check_re "준비: 회차·사용자·입장 토큰" '^.+$' "$sid$a$ta"
    say "notification 중단"
    compose stop notification
    order=$(purchase "$sid" "$a" "$ta" 1) && ok "결제 요청까지 완료: $order" || bad "예매 실패: $order"
    check "주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" 'CONFIRMED|PAID|APPROVED' 60 || true)"
    note "search-assist: 해당 없음(미구현)"
    say "notification 복구: 쌓인 이벤트로 메일이 온다"
    compose start notification
    mails=0
    for _ in $(seq 1 30); do
        mails=$(curl -s "http://localhost:${MAILPIT_UI_HOST_PORT:-$(grep -s '^MAILPIT_UI_HOST_PORT=' .env | cut -d= -f2)}/api/v1/messages" | jq -r '.messages_count // 0')
        [ "${mails:-0}" -ge 1 ] && break; sleep 2
    done
    echo "$mails" > "$OUT/mails.txt"
    [ "${mails:-0}" -ge 1 ] && ok "복구 뒤 메일 ${mails}통" || bad "복구 뒤 메일이 오지 않았다"
    exit_status
}

# 기대: Redis 중단 중 새 진입은 503(서킷 덕에 p95 ${ENTRY_P95_MS}ms 이하), queue liveness는 UP·readiness는 503, 토큰 보유자의 점유·주문·결제는 계속,
# 속도 제한은 fail-open(429·5xx 없음, 메트릭 증가). 복구 뒤 진입 가능. Redis 유실은 event.session 되감기로 복구(토픽은 compaction).
scenario_redis() {
    FAILED=0
    local ENTRY_P95_MS="${ENTRY_P95_MS:-300}" API_P95_MS="${API_P95_MS:-1000}"
    say "준비: 회차 생성, 사용자 두 명"
    compose up -d --build --wait
    local sid a b ta tb
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-a-$$@example.com"); b=$(signup_login "fault-b-$$@example.com")
    ta=$(enter_token "$sid" "$a")
    check_re "준비: 회차·사용자" '^.+$' "$sid$a$b"
    check_re "A 입장 토큰 확보" '^.+$' "$ta"

    say "Redis 중단"
    compose stop redis
    probe 30 POST "/api/v1/queue/sessions/$sid/entries" "$b" "$OUT/enter-during-outage.txt"
    assert_probe "새 진입은 503, 서킷으로 빠르게" "$OUT/enter-during-outage.txt" '^503$' "$ENTRY_P95_MS" 30
    check "queue liveness" 200 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health/liveness)"
    check "queue health(Redis 포함)" 503 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health)"
    say "토큰 보유자(A): 점유·주문·결제 계속"
    local order; order=$(purchase "$sid" "$a" "$ta" 1) && ok "A 결제 요청까지 완료: $order" || bad "A 예매 실패: $order"
    check "A 주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" 'CONFIRMED|PAID|APPROVED' 60 || true)"
    say "속도 제한 fail-open"
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-during-outage.txt"
    assert_probe "좌석 상태 조회는 200(429·5xx 없음)" "$OUT/seat-status-during-outage.txt" '^200$' "$API_P95_MS" 30
    metric gateway 8089 'gateway_ratelimit_failopen' > "$OUT/failopen-metric.txt"
    check_nonempty "fail-open 메트릭이 올랐다" "$OUT/failopen-metric.txt"
    metric queue 8083 'queue_redis_circuit|redis_circuit' > "$OUT/queue-circuit-metric.txt"
    check_nonempty "queue Redis 서킷 열림 지표" "$OUT/queue-circuit-metric.txt"

    say "Redis 복구"
    compose start redis
    for _ in $(seq 1 30); do [ "$(compose ps redis --format '{{.Health}}')" = healthy ] && break; sleep 1; done
    sleep 6
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check_re "복구 뒤 B 진입 성공(서킷 닫힘)" '^20[01]$' "$CODE"

    say "Redis 데이터 유실 후 event.session 재소비로 복구"
    compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --describe --entity-type topics --entity-name event.session > "$OUT/topic-config.txt"
    grep -q 'cleanup.policy=compact' "$OUT/topic-config.txt" && ok "event.session compaction" || bad "event.session이 compaction이 아니다"
    compose exec -T redis redis-cli FLUSHALL > /dev/null
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check "유실 직후 진입은 회차 없음" 404 "$CODE"
    compose stop queue
    compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group queue --topic event.session --reset-offsets --to-earliest --execute > "$OUT/reset-offsets.txt" 2>&1
    check_nonempty "오프셋 되감기 결과" "$OUT/reset-offsets.txt"
    compose start queue
    for _ in $(seq 1 30); do
        api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
        [[ $CODE =~ ^20[01]$ ]] && break; sleep 2
    done
    check_re "오프셋 되감기 뒤 진입 복구" '^20[01]$' "$CODE"
    exit_status
}

# 기대: PG 지연 8초 동안 결제는 UNKNOWN으로 가도 seat-status·공연 조회는 200이고 p95 ${API_P95_MS}ms 이하, payment 스레드·Bulkhead 지표가 있다.
# 실패율 100%로 바꾼 뒤 복구하면 모든 주문이 ${PG_CONVERGE_S}초 안에 CONFIRMED 또는 FAILED로 끝난다(PAYMENT_REQUESTED·REFUNDING에 남지 않는다).
# 서킷이 열렸는지는 임계값을 확인하지 못해 NOTE로만 남긴다.
scenario_pg() {
    FAILED=0
    local API_P95_MS="${API_P95_MS:-1000}" PG_CONVERGE_S="${PG_CONVERGE_S:-720}"
    compose up -d --build --wait
    local sid a ta o i pg
    pg="http://localhost:${MOCK_PG_HOST_PORT:-$(grep -s '^MOCK_PG_HOST_PORT=' .env | cut -d= -f2)}"
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-pg-$$@example.com"); ta=$(enter_token "$sid" "$a")
    check_re "준비: 회차·사용자·입장 토큰" '^.+$' "$sid$a$ta"

    say "PG 지연 8초(읽기 타임아웃 5초 초과)"
    check "지연 설정 적용" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":8000,"failureRate":0,"timeoutRate":0,"dropResponseRate":0}')"
    local orders=()
    for i in 1 2 3 4 5; do
        o=$(purchase "$sid" "$a" "$ta" "$i") && orders+=("$o") || bad "주문 $i 생성 실패: $o"
    done
    say "결제가 걸려 있는 동안 다른 API"
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-during-delay.txt"
    probe 30 GET /api/v1/performances "$a" "$OUT/performances-during-delay.txt"
    assert_probe "좌석 상태 조회" "$OUT/seat-status-during-delay.txt" '^200$' "$API_P95_MS" 30
    assert_probe "공연 목록 조회" "$OUT/performances-during-delay.txt" '^200$' "$API_P95_MS" 30
    metric payment 8085 'tomcat_threads_busy|resilience4j_bulkhead_available|resilience4j_circuitbreaker_state' > "$OUT/payment-metrics-during-delay.txt"
    check_nonempty "payment 스레드·Bulkhead·서킷 지표" "$OUT/payment-metrics-during-delay.txt"

    say "실패율 100%"
    curl -s -o /dev/null -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":0,"failureRate":1.0,"timeoutRate":0,"dropResponseRate":0}'
    for i in 6 7 8 9; do
        o=$(purchase "$sid" "$a" "$ta" "$i") && orders+=("$o") || bad "주문 $i 생성 실패: $o"
    done
    check "생성한 주문 수" 9 "${#orders[@]}"
    sleep 5
    metric payment 8085 'resilience4j_circuitbreaker_state|resilience4j_circuitbreaker_calls' > "$OUT/payment-circuit.txt"
    grep -qE 'state="open".* 1\.0' "$OUT/payment-circuit.txt" && ok "서킷이 열렸다" || note "서킷 열림을 확인하지 못했다(임계값 미확인)"
    probe 15 GET /api/v1/performances "$a" "$OUT/performances-during-failure.txt"
    assert_probe "PG 실패 중 공연 목록 조회" "$OUT/performances-during-failure.txt" '^200$' "$API_P95_MS" 15

    say "복구 후 수렴 대기(최대 ${PG_CONVERGE_S}초)"
    curl -s -o /dev/null -X DELETE "$pg/pg/admin/faults"
    local t0=$SECONDS pending
    while :; do
        pending=0
        for o in "${orders[@]}"; do
            [[ "$(order_status "$o" "$a" 2>/dev/null || true)" =~ ^(CONFIRMED|FAILED)/ ]] || pending=$((pending + 1))
        done
        echo "$((SECONDS - t0))s 미종결=$pending" >> "$OUT/convergence.txt"
        [ "$pending" = 0 ] && break
        [ $((SECONDS - t0)) -ge "$PG_CONVERGE_S" ] && break
        sleep 5
    done
    for o in "${orders[@]}"; do echo "$o: $(order_status "$o" "$a" 2>/dev/null || true)"; done | tee "$OUT/orders-final.txt"
    check "미종결 주문" 0 "$pending"
    exit_status
}

# 기대: KOPIS가 5xx·깨진 XML·타임아웃이어도 동기화 실행은 기록되고(실행 수 +1), 공연 수는 그대로, 공연 조회 200, 대기열·점유·결제 요청이 정상이다.
# 복구(ok) 뒤 동기화가 다시 돌고 공연 수가 줄지 않는다.
scenario_kopis() {
    FAILED=0
    COMPOSE_FILES=(-f compose.yml -f perf/fault/kopis-override.yml)
    compose up -d --build --wait event kopis-stub
    local admin sid a ta before runs now mode
    admin=$(admin_login)
    stub() { compose exec -T kopis-stub python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:9000/__mode/$1').read().decode())"; }
    count_runs() { sql_event "SELECT COUNT(*) FROM kopis_sync_run"; }
    count_perf() { sql_event "SELECT COUNT(*) FROM performance"; }
    run_sync() { # run_sync 대기초 → 마지막 실행 행. 실행이 기록되지 않으면 비운다
        local c0; c0=$(count_runs)
        api POST /api/v1/admin/kopis/sync "$admin" "" > /dev/null
        for _ in $(seq 1 "$1"); do [ "$(count_runs)" -gt "${c0:-0}" ] && break; sleep 1; done
        [ "$(count_runs)" -gt "${c0:-0}" ] && sql_event "SELECT status, calls, parse_failures, error FROM kopis_sync_run ORDER BY id DESC LIMIT 1"
    }
    stub ok > /dev/null
    say "정상 동기화로 기준 데이터 적재"
    check_re "정상 동기화 실행 기록" '^.+$' "$(run_sync 30)"
    before=$(count_perf)
    check_re "기준 공연 수" '^[1-9][0-9]*$' "$before"
    sid=$(make_session 15); wait_open 15
    a=$(signup_login "fault-kopis-$$@example.com")
    check_re "준비: 회차·사용자" '^.+$' "$sid$a"
    for mode in 5xx broken timeout; do
        say "KOPIS $mode"; stub "$mode" > /dev/null
        runs=$(run_sync 60); echo "$mode: $runs" | tee -a "$OUT/sync-runs.txt"
        check_re "동기화 실행이 기록됐다($mode)" '^.+$' "$runs"
        check "공연 수 유지($mode)" "$before" "$(count_perf)"
        check "공연 조회 200($mode)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$gw/api/v1/performances")"
        ta=$(enter_token "$sid" "$a")
        check_re "대기열·입장 정상($mode)" '^.+$' "$ta"
        o=$(purchase "$sid" "$a" "$ta" "$((RANDOM % 1000 + 100))") && ok "점유·주문·결제 요청 정상($mode)" || bad "예매 경로 실패($mode): $o"
    done
    say "복구"
    stub ok > /dev/null
    check_re "복구 뒤 동기화 실행 기록" '^.+$' "$(run_sync 30)"
    now=$(count_perf)
    [ "${now:-0}" -ge "$before" ] && ok "복구 뒤 공연 수 ${now} >= ${before}" || bad "복구 뒤 공연 수가 줄었다: ${before} → ${now}"
    exit_status
}
