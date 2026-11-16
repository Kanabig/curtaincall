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

# 상태 코드는 $(api ...)의 서브셸에서 부모로 전달되지 않으므로 파일로 넘긴다. 마지막 호출의 코드는 last_code로 읽는다
CODE_FILE="/tmp/fault-code.$$"
last_code() { cat "$CODE_FILE" 2>/dev/null || echo 000; }
api() { # api METHOD PATH [token] [json] [extra header...]  → 본문 출력, 상태 코드는 last_code
    local method="$1" path="$2" token="${3:-}" body="${4:-}"; shift 4 2>/dev/null || shift $#
    local args=(-s -o /tmp/fault-body.$$ -w '%{http_code}' -X "$method" "$gw$path" -H 'Content-Type: application/json')
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$body" ] && args+=(-d "$body")
    for h in "$@"; do args+=(-H "$h"); done
    curl "${args[@]}" > "$CODE_FILE" || echo 000 > "$CODE_FILE"
    cat /tmp/fault-body.$$ 2>/dev/null
}

signup_login() { # signup_login 이메일 → access token
    api POST /api/v1/users "" "{\"email\":\"$1\",\"password\":\"password-1234\",\"name\":\"fault\"}" > /dev/null
    api POST /api/v1/auth/login "" "{\"email\":\"$1\",\"password\":\"password-1234\"}" | jq -r '.accessToken // empty'
}

admin_login() {
    local email password
    email=$(grep -s '^ADMIN_EMAIL=' .env | cut -d= -f2); password=$(grep -s '^ADMIN_PASSWORD=' .env | cut -d= -f2)
    api POST /api/v1/auth/login "" "{\"email\":\"$email\",\"password\":\"$password\"}" | jq -r '.accessToken // empty'
}

# 판매 오픈이 $1초 뒤인 회차를 만들고 ID를 출력한다. fixture 공연(PF305720, 가격 등급 1층석)을 쓴다.
# PF305718·PF305735는 공연장 배치를 만들 수 없어(HALL_LAYOUT_UNAVAILABLE) 쓰지 않는다. 등록이 거절되면 KOPIS 동기화를 한 번 돌리고 다시 시도한다.
# 실패하면 아무것도 출력하지 않는다(호출한 쪽의 check가 잡는다).
make_session() {
    local open_in="${1:-20}" admin perf start open sid body
    admin=$(admin_login)
    [ -n "$admin" ] && [ "$admin" != null ] || return 0
    perf=$(api POST /api/v1/admin/performances "$admin" '{"kopisId":"PF305720"}' | jq -r '.id // empty')
    if [ -z "$perf" ]; then
        api POST /api/v1/admin/kopis/sync "$admin" "" > /dev/null; sleep 20
        perf=$(api POST /api/v1/admin/performances "$admin" '{"kopisId":"PF305720"}' | jq -r '.id // empty')
    fi
    # 이미 등록돼 있으면(재실행) 목록에서 찾는다
    [ -n "$perf" ] || perf=$(api GET "/api/v1/performances?size=50" "" "" | jq -r '[.content[] | select(.kopisId == "PF305720")][0].id // empty')
    [ -n "$perf" ] || return 0
    open=$(date -d "+${open_in} seconds" --iso-8601=seconds)
    start=$(date -d "+3 days" --iso-8601=seconds)
    body=$(api POST "/api/v1/admin/performances/$perf/sessions" "$admin" \
        "{\"startAt\":\"$start\",\"saleOpenAt\":\"$open\",\"prices\":[{\"grade\":\"1층석\",\"price\":10000}],\"admissionPerSecond\":50,\"maxActiveUsers\":300}")
    sid=$(echo "$body" | jq -r '.sessionId // .id // empty')
    [ "$(last_code)" = 201 ] && echo "$sid" || echo "make_session 실패($(last_code)): $body" >&2
}

# 회차의 실제 좌석 ID를 reservation 재고에서 읽는다(hall_seat.seat_id는 자동 증가라 1부터라고 가정하지 않는다). 재고 생성은 비동기라 최대 90초 기다린다
seat_ids() { # seat_ids 회차 개수 → 좌석 ID를 공백으로 구분해 출력
    local ids="" i
    for i in $(seq 1 45); do
        ids=$(compose exec -T mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N reservation_db -e \"SELECT seat_id FROM seat_inventory WHERE session_id = $1 ORDER BY seat_id LIMIT $2\" 2>/dev/null" | tr -d '\r' | tr '\n' ' ')
        [ "$(wc -w <<< "$ids")" -ge "$2" ] && break
        ids=""; sleep 2
    done
    echo "$ids"
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
    [ "$(last_code)" = 201 ] || { echo "hold:$(last_code)"; return 1; }
    order=$(api POST /api/v1/orders "$2" "{\"holdId\":\"$hold\"}" "X-Entry-Token: $3" "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" | jq -r .orderId)
    [ "$(last_code)" = 201 ] || { echo "order:$(last_code)"; return 1; }
    api POST "/api/v1/orders/$order/payment" "$2" '{"method":"CARD","cardToken":"tok_approve"}' "X-Entry-Token: $3" "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" > /dev/null
    [ "$(last_code)" = 202 ] || { echo "payment:$(last_code)"; return 1; }
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


# 사용자별 속도 제한(초당 10, 버스트 20)에 걸리지 않도록 호출 사이에 간격을 둔다(PROBE_GAP초)
probe() { # probe 횟수 METHOD PATH 토큰 파일 → 파일에 "상태코드 초"를 한 줄씩 남긴다
    local n="$1" f="$5" code
    : > "$f"
    for _ in $(seq 1 "$n"); do
        code=$(curl -s -o /dev/null -w '%{http_code} %{time_total}' --max-time 10 -X "$2" "$gw$3" \
            -H "Authorization: Bearer $4" -H 'Content-Type: application/json') || code="000 10"
        echo "$code" >> "$f"
        sleep "${PROBE_GAP:-0.15}"
    done
}

# 판정(FAIL)은 절대 기준(p95상한ms)으로만 한다. baseline p95(ms)를 주면 3배를 넘을 때 NOTE만 남긴다.
assert_probe() { # assert_probe 설명 파일 허용코드정규식 p95상한ms 기대횟수 [baseline p95 ms]
    local label="$1" f="$2" re="$3" max="$4" want="$5" base="${6:-}" n wrong p95
    n=$(wc -l < "$f")
    [ "$n" -ge "$want" ] || { bad "$label: 측정이 $n회뿐이다(기대 $want)"; return; }
    wrong=$(awk -v re="$re" '$1 !~ re {c++} END {print c+0}' "$f")
    p95=$(awk '{print int($2 * 1000)}' "$f" | sort -n | awk '{a[NR] = $1} END {i = int(NR * 0.95); if (i < 1) i = 1; print a[i]}')
    awk '{c[$1]++} END {for (k in c) printf "%s×%d ", k, c[k]; print ""}' "$f" > "$f.codes"
    if [ "$wrong" -gt 0 ]; then bad "$label: 허용 밖 상태 코드 ${wrong}건 ($(cat "$f.codes"))"
    elif [ "$p95" -gt "$max" ]; then bad "$label: p95 ${p95}ms > ${max}ms ($(cat "$f.codes"))"
    else ok "$label: p95 ${p95}ms <= ${max}ms ($(cat "$f.codes"))"; fi
    LAST_P95="$p95"
    if [ -n "$base" ] && [ "$p95" -gt $((base * 3)) ]; then note "$label: p95 ${p95}ms가 baseline ${base}ms의 3배를 넘는다"; fi
}

metric_sum() { # metric_sum 서비스 포트 지표이름접두 → 합계(없으면 0). 접두는 줄 시작 기준
    compose exec -T "$1" curl -s "http://localhost:$2/actuator/prometheus" | awk -v p="^$3" '$1 ~ p {s += $NF} END {print s + 0}'
}

new_buyer() { # new_buyer 회차 번호 → "access토큰 입장토큰"(1인 4매 한도를 피하려고 주문마다 사용자를 만든다)
    local t; t=$(signup_login "fault-buyer-$2-$$@example.com")
    echo "$t $(enter_token "$1" "$t")"
}

sql_event() { compose exec -T mysql sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --default-character-set=utf8mb4 -N event_db -e \"$1\" 2>/dev/null" | tr -d '\r'; }

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
# 이전 실행의 메시지에 속지 않도록 실행마다 다른 키(dlt-<시각>)를 쓰고 그 키가 DLT에 있는지 본다. 끝에 같은 키의 tombstone을 보내
# compact 토픽에 불량 메시지가 남지 않게 한다(compaction은 비동기라 곧바로 지워지지는 않는다).
scenario_dlt() {
    FAILED=0
    compose up -d --build --wait
    local kt=/opt/kafka/bin t key="dlt-$(date +%s)"
    say "증거: 주입 전 토픽 목록"
    compose exec -T kafka $kt/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort > "$OUT/topics-before.txt"
    check_nonempty "토픽 목록 수집" "$OUT/topics-before.txt"
    say "event.session에 깨진 메시지를 넣는다(키 $key)"
    echo "$key:{not-json" | compose exec -T kafka $kt/kafka-console-producer.sh --bootstrap-server localhost:9092 \
        --topic event.session --property parse.key=true --property key.separator=:
    sleep 15
    compose exec -T kafka $kt/kafka-topics.sh --bootstrap-server localhost:9092 --list | sort > "$OUT/topics-after.txt"
    for t in event.session.queue.DLT event.session.reservation.DLT; do
        grep -qx "$t" "$OUT/topics-after.txt" && ok "토픽 존재: $t" || bad "토픽 없음: $t (토픽 목록은 topics-after.txt)"
        # 소비 시간 초과로 종료 코드가 0이 아니어도 판정은 파일 내용으로 한다
        compose exec -T kafka $kt/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$t" \
            --from-beginning --timeout-ms 8000 --property print.headers=true --property print.key=true > "$OUT/$t.txt" 2>/dev/null || true
        grep -q "$key" "$OUT/$t.txt" && ok "$t 에 이번 회차 메시지($key)가 쌓였다" || bad "$t 에 이번 회차 메시지($key)가 없다"
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
    compose exec -T reservation curl -s "http://localhost:8084/actuator/dlt/event.session.reservation.DLT?limit=200" > "$OUT/reservation-dlt-entries.txt" || true
    check_nonempty "reservation DLT 항목 조회" "$OUT/reservation-dlt-entries.txt"
    say "정리: 같은 키로 tombstone(값이 null이라 두 소비자가 봉투 파싱에 실패해 이 키도 DLT로 간다. 해롭지 않다)"
    echo "$key:NULL" | compose exec -T kafka $kt/kafka-console-producer.sh --bootstrap-server localhost:9092 \
        --topic event.session --property parse.key=true --property key.separator=: --property null.marker=NULL
    exit_status
}

# 기대: notification을 멈춰도 예매(점유→주문→결제)가 CONFIRMED로 끝나고, 복구 뒤 쌓인 이벤트로 메일이 나간다.
# search-assist는 미구현이라 해당 없음(구현 뒤 중단 시나리오 추가).
scenario_aux() {
    FAILED=0
    compose up -d --build --wait
    local sid a ta order mails
    sid=$(make_session 15); wait_open 15
    check_re "회차 ID" '^[0-9]+$' "$sid"
    a=$(signup_login "fault-aux-$$@example.com"); ta=$(enter_token "$sid" "$a")
    check_re "준비: 회차·사용자·입장 토큰" '^.+$' "$sid$a$ta"
    local seats; read -ra seats <<< "$(seat_ids "$sid" 4)"
    check "준비: 좌석 ID 수" 4 "${#seats[@]}"
    local mailui="http://localhost:${MAILPIT_UI_HOST_PORT:-$(grep -s '^MAILPIT_UI_HOST_PORT=' .env | cut -d= -f2)}"
    # 이전 실행의 메일로 통과하지 않도록 시작 전에 비운다
    curl -s -o /dev/null -X DELETE "$mailui/api/v1/messages"
    check "메일함 비움" 0 "$(curl -s "$mailui/api/v1/messages" | jq -r '.messages_count // empty')"
    say "notification 중단"
    compose stop notification
    order=$(purchase "$sid" "$a" "$ta" "${seats[0]}") && ok "결제 요청까지 완료: $order" || bad "예매 실패: $order"
    check "주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" '^CONFIRMED/APPROVED$' 60 || true)"
    note "search-assist: 해당 없음(미구현)"
    say "notification 복구: 쌓인 이벤트로 메일이 온다"
    compose start notification
    mails=0
    for _ in $(seq 1 30); do
        mails=$(curl -s "$mailui/api/v1/messages" | jq -r '.messages_count // 0')
        [ "${mails:-0}" -ge 1 ] && break; sleep 2
    done
    echo "$mails" > "$OUT/mails.txt"
    [ "${mails:-0}" -ge 1 ] && ok "복구 뒤 메일 ${mails}통" || bad "복구 뒤 메일이 오지 않았다"
    exit_status
}

# 기대: Redis 중단 중 새 진입은 503(연속 실패 한도 N건은 타임아웃을 기다리고, 서킷이 열린 뒤에는 p95 ${ENTRY_P95_MS}ms 이하), queue liveness·readiness는 200(Redis 제외)이고 /actuator/health(Redis 포함)는 503, 토큰 보유자의 점유·주문·결제는 계속,
# 속도 제한은 fail-open(429·5xx 없음, 메트릭 증가). 복구 뒤 진입 가능. Redis 유실은 event.session 되감기로 복구(토픽은 compaction).
scenario_redis() {
    FAILED=0
    # 기준값: 진입 p95 200ms는 NFR-5(spec/requirements.md), 그 밖의 API는 1000ms. 프로브 부하가 낮아 명세 값을 그대로 쓴다
    local ENTRY_P95_MS="${ENTRY_P95_MS:-200}" API_P95_MS="${API_P95_MS:-1000}"
    say "준비: 회차 생성, 사용자 두 명"
    compose up -d --build --wait
    local sid a b ta tb
    sid=$(make_session 15); wait_open 15
    check_re "회차 ID" '^[0-9]+$' "$sid"
    a=$(signup_login "fault-a-$$@example.com"); b=$(signup_login "fault-b-$$@example.com")
    ta=$(enter_token "$sid" "$a")
    check_re "준비: 회차·사용자" '^.+$' "$sid$a$b"
    check_re "A 입장 토큰 확보" '^.+$' "$ta"
    local seats; read -ra seats <<< "$(seat_ids "$sid" 4)"
    check "준비: 좌석 ID 수" 4 "${#seats[@]}"

    say "baseline(Redis 정상): 좌석 상태 조회"
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-baseline.txt"
    assert_probe "baseline 좌석 상태 조회" "$OUT/seat-status-baseline.txt" '^200$' "$API_P95_MS" 30
    local base=$LAST_P95

    local failopen0 open0
    failopen0=$(metric_sum gateway 8089 gateway_ratelimit_failopen_total | cut -d. -f1)
    open0=$(metric_sum queue 8083 queue_redis_circuit_open_total | cut -d. -f1)
    say "Redis 중단"
    compose stop redis
    # 서킷이 열리기 전(연속 실패 N회)에는 Lettuce 타임아웃을 그대로 기다린다. 열리기 전 비용과 열린 뒤 경로를 나눠서 본다
    local before_open=0 threshold="${REDIS_CIRCUIT_FAILURES:-3}" opened=0 now_open
    : > "$OUT/enter-before-open.txt"
    local t_open0; t_open0=$(date +%s%N)
    for _ in $(seq 1 $((threshold + 5))); do
        probe 1 POST "/api/v1/queue/sessions/$sid/entries" "$b" "$OUT/enter-one.txt"
        cat "$OUT/enter-one.txt" >> "$OUT/enter-before-open.txt"
        before_open=$((before_open + 1))
        now_open=$(( $(metric_sum queue 8083 queue_redis_circuit_open_total | cut -d. -f1) - open0 ))
        [ "$now_open" -ge 1 ] && { opened=1; break; }
    done
    local open_ms=$(( ($(date +%s%N) - t_open0) / 1000000 ))
    echo "서킷이 열리기까지 ${open_ms}ms, 열리기 전 진입 ${before_open}건 지연(ms): $(awk '{printf "%d ", $2 * 1000}' "$OUT/enter-before-open.txt")" | tee "$OUT/open-latency.txt"
    check "서킷이 열렸다" 1 "$opened"
    [ "$before_open" -le "$threshold" ] && ok "열리기 전 느린 진입 ${before_open}건 <= 연속 실패 한도 ${threshold}" || bad "열리기 전 진입 ${before_open}건이 한도 ${threshold}를 넘었다"
    probe 30 POST "/api/v1/queue/sessions/$sid/entries" "$b" "$OUT/enter-during-outage.txt"
    assert_probe "열린 뒤 새 진입은 503, 서킷으로 빠르게" "$OUT/enter-during-outage.txt" '^503$' "$ENTRY_P95_MS" 30
    check "queue liveness" 200 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health/liveness)"
    # readiness 그룹은 트래픽 수락 상태만 본다(Redis 제외). 장애 중에도 UP이어야 컨테이너가 재시작되지 않는다
    check "queue readiness" 200 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health/readiness)"
    check "queue health(Redis 포함)" 503 "$(compose exec -T queue curl -s -o /dev/null -w '%{http_code}' http://localhost:8083/actuator/health)"
    say "토큰 보유자(A): 점유·주문·결제 계속"
    local order; order=$(purchase "$sid" "$a" "$ta" "${seats[0]}") && ok "A 결제 요청까지 완료: $order" || bad "A 예매 실패: $order"
    check "A 주문 확정" "CONFIRMED/APPROVED" "$(wait_order "$order" "$a" '^CONFIRMED/APPROVED$' 60 || true)"
    say "속도 제한 fail-open"
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-during-outage.txt"
    assert_probe "좌석 상태 조회는 200(429·5xx 없음)" "$OUT/seat-status-during-outage.txt" '^200$' "$API_P95_MS" 30 "$base"
    # 지표는 재기동 전까지 누적되므로 주입 전 값과의 차이로 판정한다
    check_re "gateway.ratelimit.failopen 증가분" '^[1-9][0-9]*$' "$(($(metric_sum gateway 8089 gateway_ratelimit_failopen_total | cut -d. -f1) - failopen0))"
    check_re "queue.redis.circuit.open 증가분" '^[1-9][0-9]*$' "$(($(metric_sum queue 8083 queue_redis_circuit_open_total | cut -d. -f1) - open0))"

    say "Redis 복구"
    compose start redis
    for _ in $(seq 1 30); do [ "$(compose ps redis --format '{{.Health}}')" = healthy ] && break; sleep 1; done
    # Lettuce 재연결 백오프(최대 30초)와 서킷 때문에 Redis가 돌아온 직후에는 503이 이어질 수 있다. 복구까지 걸린 시간을 재고 RECOVER_S 안이면 통과한다
    local t0=$SECONDS RECOVER_S="${RECOVER_S:-60}"
    until api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null; [[ "$(last_code)" =~ ^20[01]$ ]] || [ $((SECONDS - t0)) -ge "$RECOVER_S" ]; do sleep 1; done
    echo "복구까지 $((SECONDS - t0))초" | tee "$OUT/recovery-time.txt"
    check_re "복구 뒤 B 진입 성공(${RECOVER_S}초 안)" '^20[01]$' "$(last_code)"

    say "Redis 데이터 유실 후 event.session 재소비로 복구"
    compose exec -T kafka /opt/kafka/bin/kafka-configs.sh --bootstrap-server localhost:9092 --describe --entity-type topics --entity-name event.session > "$OUT/topic-config.txt"
    grep -q 'cleanup.policy=compact' "$OUT/topic-config.txt" && ok "event.session compaction" || bad "event.session이 compaction이 아니다"
    compose exec -T redis redis-cli FLUSHALL > /dev/null
    api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
    check "유실 직후 진입은 회차 없음" 404 "$(last_code)"
    compose stop queue
    compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --group queue --topic event.session --reset-offsets --to-earliest --execute > "$OUT/reset-offsets.txt" 2>&1
    check_nonempty "오프셋 되감기 결과" "$OUT/reset-offsets.txt"
    compose start queue
    for _ in $(seq 1 30); do
        api POST "/api/v1/queue/sessions/$sid/entries" "$b" "" > /dev/null
        [[ "$(last_code)" =~ ^20[01]$ ]] && break; sleep 2
    done
    check_re "오프셋 되감기 뒤 진입 복구" '^20[01]$' "$(last_code)"
    exit_status
}

# 기대: PG 지연 8초 동안 결제는 UNKNOWN으로 가도 seat-status·공연 조회는 200이고 p95 ${API_P95_MS}ms 이하다(baseline의 3배를 넘으면 NOTE).
# 실패율 100%로 20건을 더 보내면 서킷이 열려 not_permitted 호출이 생기고, 복구 뒤 모든 주문이 PG_CONVERGE_S 안에 CONFIRMED 또는 FAILED로 끝난다.
# PG_CONVERGE_S 산정(ADR-0008, PaymentProperties): 거래 조회는 1,2,4,8,16초 뒤 30초 간격으로 maxDuration 10분(600초)까지 계속한다.
# 이 창 안에 오류 응답(5xx·타임아웃)이 한 번이라도 섞이면 "내내 404"가 아니라 UNKNOWN으로 남고, 오케스트레이터의 재전송(2분)이 조회를 처음부터 다시 시작해
# 다음 10분 창에서 내내 404여야 망취소로 확정된다. 실측(w1, 주문 20건을 오류 구간에 넣은 실행): 첫 창은 오류 구간에 걸려 오염됐고, 오류가 끝난 뒤 시작한 두 번째 창도
# 확정되지 못했고(원인: 조회 Bulkhead 거절도 오류로 세어 창이 오염됨, 후속 이슈), 세 번째 창에서 확정됐다. 3창 = 약 1800초. 취소 재시도 간격은 1,2,4,8,16,30,30초(최대 8회) 합 91초이므로 여유를 두고 1900초.
scenario_pg() {
    FAILED=0
    local API_P95_MS="${API_P95_MS:-1000}" PG_CONVERGE_S="${PG_CONVERGE_S:-1900}"
    compose up -d --build --wait
    local sid pg i b buyers=() tokens=() orders=() o a ta base
    pg="http://localhost:${MOCK_PG_HOST_PORT:-$(grep -s '^MOCK_PG_HOST_PORT=' .env | cut -d= -f2)}"
    sid=$(make_session 15); wait_open 15
    check_re "회차 ID" '^[0-9]+$' "$sid"
    check_re "준비: 회차" '^.+$' "$sid"
    for i in $(seq 1 25); do
        b=$(new_buyer "$sid" "$i"); buyers+=("${b% *}"); tokens+=("${b#* }")
    done
    a="${buyers[0]}"
    local seats; read -ra seats <<< "$(seat_ids "$sid" 25)"
    check "준비: 좌석 ID 수" 25 "${#seats[@]}"
    check_re "준비: 사용자 25명의 입장 토큰" '^.+$' "$(printf '%s' "${tokens[*]}" | tr -d ' ')"

    say "baseline(PG 정상)"
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-baseline.txt"
    assert_probe "baseline 좌석 상태 조회" "$OUT/seat-status-baseline.txt" '^200$' "$API_P95_MS" 30
    base=$LAST_P95

    local npc0
    npc0=$(metric_sum payment 8085 resilience4j_circuitbreaker_not_permitted_calls_total | cut -d. -f1)
    say "PG 지연 8초(읽기 타임아웃 5초 초과): 주문 5건"
    check "지연 설정 적용" 200 "$(curl -s -o /dev/null -w '%{http_code}' -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":8000,"failureRate":0,"timeoutRate":0,"dropResponseRate":0}')"
    for i in 1 2 3 4 5; do
        o=$(purchase "$sid" "${buyers[$((i - 1))]}" "${tokens[$((i - 1))]}" "${seats[$((i - 1))]}") && orders+=("$o|${buyers[$((i - 1))]}") || bad "주문 $i 생성 실패: $o"
    done
    probe 30 GET "/api/v1/sessions/$sid/seat-status" "$a" "$OUT/seat-status-during-delay.txt"
    probe 30 GET /api/v1/performances "$a" "$OUT/performances-during-delay.txt"
    assert_probe "지연 중 좌석 상태 조회" "$OUT/seat-status-during-delay.txt" '^200$' "$API_P95_MS" 30 "$base"
    assert_probe "지연 중 공연 목록 조회" "$OUT/performances-during-delay.txt" '^200$' "$API_P95_MS" 30
    metric payment 8085 'tomcat_threads_busy|resilience4j_bulkhead_available|resilience4j_circuitbreaker_state' > "$OUT/payment-metrics-during-delay.txt"
    check_nonempty "payment 스레드·Bulkhead·서킷 지표" "$OUT/payment-metrics-during-delay.txt"
    # payment 자체가 지연 중에도 응답하는지(PG를 거치지 않는 liveness)와 Bulkhead 여유(고갈되지 않음)
    : > "$OUT/payment-liveness-during-delay.txt"
    for _ in $(seq 1 15); do
        compose exec -T payment curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 10 http://localhost:8085/actuator/health >> "$OUT/payment-liveness-during-delay.txt" || echo "000 10" >> "$OUT/payment-liveness-during-delay.txt"
        sleep 0.3
    done
    assert_probe "지연 중 payment health" "$OUT/payment-liveness-during-delay.txt" '^200$' "$API_P95_MS" 15
    check_re "승인 Bulkhead 여유(고갈되지 않음)" '^[1-9][0-9]*$' "$(grep 'resilience4j_bulkhead_available_concurrent_calls.*pg-approval' "$OUT/payment-metrics-during-delay.txt" | awk '{print int($NF)}')"

    say "실패율 100%: 주문 20건(서킷 윈도 20건을 채운다)"
    curl -s -o /dev/null -X PUT "$pg/pg/admin/faults" -H 'Content-Type: application/json' -d '{"delayMs":0,"failureRate":1.0,"timeoutRate":0,"dropResponseRate":0}'
    for i in $(seq 6 25); do
        o=$(purchase "$sid" "${buyers[$((i - 1))]}" "${tokens[$((i - 1))]}" "${seats[$((i - 1))]}") && orders+=("$o|${buyers[$((i - 1))]}") || bad "주문 $i 생성 실패: $o"
    done
    check "생성한 주문 수" 25 "${#orders[@]}"
    sleep 5
    metric payment 8085 'resilience4j_circuitbreaker' > "$OUT/payment-circuit.txt"
    check_re "서킷이 호출을 막았다(not_permitted 증가분)" '^[1-9][0-9]*$' "$(($(metric_sum payment 8085 resilience4j_circuitbreaker_not_permitted_calls_total | cut -d. -f1) - npc0))"
    probe 15 GET /api/v1/performances "$a" "$OUT/performances-during-failure.txt"
    assert_probe "PG 실패 중 공연 목록 조회" "$OUT/performances-during-failure.txt" '^200$' "$API_P95_MS" 15 "$base"

    say "복구 후 수렴 대기(최대 ${PG_CONVERGE_S}초)"
    curl -s -o /dev/null -X DELETE "$pg/pg/admin/faults"
    local t0=$SECONDS pending e
    while :; do
        pending=0
        for e in "${orders[@]}"; do
            [[ "$(order_status "${e%|*}" "${e#*|}" 2>/dev/null || true)" =~ ^(CONFIRMED|FAILED)/ ]] || pending=$((pending + 1))
        done
        echo "$((SECONDS - t0))s 미종결=$pending" >> "$OUT/convergence.txt"
        [ "$pending" = 0 ] && break
        [ $((SECONDS - t0)) -ge "$PG_CONVERGE_S" ] && break
        sleep 5
    done
    for e in "${orders[@]}"; do echo "${e%|*}: $(order_status "${e%|*}" "${e#*|}" 2>/dev/null || true)"; done > "$OUT/orders-final.txt"
    check "미종결 주문" 0 "$pending"
    exit_status
}

# 기대: KOPIS가 5xx·깨진 XML·타임아웃이어도 동기화 실행이 끝까지 기록되고(RUNNING에 머물지 않음), 5xx·타임아웃은 FAILED와 error, 깨진 XML은
# FAILED·PARTIAL 또는 parse_failures>0이며 호출 수(calls)가 장부에 남는다. 공연 수는 그대로, 공연 조회 200, 대기열·점유·결제 요청이 정상이다.
# 단독 실행도 되도록 전체 스택을 띄운다(override로 event가 스텁을 본다). 복구(ok) 뒤 동기화가 다시 SUCCESS 또는 PARTIAL로 끝나고 공연 수가 줄지 않는다. 읽기 타임아웃 3초(override)와 재시도를 감안해 실행당 최대 90초 기다린다.
scenario_kopis() {
    FAILED=0
    COMPOSE_FILES=(-f compose.yml -f perf/fault/kopis-override.yml)
    compose up -d --build --wait  # event만이 아니라 gateway·예매 경로 서비스도 필요하다(단독 실행 대비)
    local admin sid a ta before mode row o seat=0
    admin=$(admin_login)
    stub() { compose exec -T kopis-stub python -c "import urllib.request;print(urllib.request.urlopen('http://localhost:9000/__mode/$1').read().decode())"; }
    count_runs() { sql_event "SELECT COUNT(*) FROM kopis_sync_run"; }
    count_perf() { sql_event "SELECT COUNT(*) FROM performance"; }
    run_sync() { # run_sync 대기초 → 끝난 마지막 실행 행(status calls parse_failures error). 시작·종료가 확인되지 않으면 비운다
        local c0 i status; c0=$(count_runs)
        api POST /api/v1/admin/kopis/sync "$admin" "" > /dev/null
        [ "$(last_code)" = 202 ] || return 0
        for i in $(seq 1 "$1"); do
            if [ "$(count_runs)" -gt "${c0:-0}" ]; then
                status=$(sql_event "SELECT status FROM kopis_sync_run ORDER BY id DESC LIMIT 1")
                [ -n "$status" ] && [ "$status" != RUNNING ] && break
            fi
            sleep 1
        done
        [ -n "${status:-}" ] && [ "$status" != RUNNING ] && sql_event "SELECT status, calls, parse_failures, IFNULL(error, '') FROM kopis_sync_run ORDER BY id DESC LIMIT 1"
    }
    stub ok > /dev/null
    say "정상 동기화로 기준 데이터 적재"
    row=$(run_sync 90)
    check_re "정상 동기화가 끝났다" '^(SUCCESS|PARTIAL)' "$row"
    before=$(count_perf)
    check_re "기준 공연 수" '^[1-9][0-9]*$' "$before"
    sid=$(make_session 15); wait_open 15
    check_re "회차 ID" '^[0-9]+$' "$sid"
    a=$(signup_login "fault-kopis-$$@example.com")
    check_re "준비: 회차·사용자" '^.+$' "$sid$a"
    local seats; read -ra seats <<< "$(seat_ids "$sid" 3)"
    check "준비: 좌석 ID 수" 3 "${#seats[@]}"
    for mode in 5xx broken timeout; do
        say "KOPIS $mode"; stub "$mode" > /dev/null
        row=$(run_sync 90); echo "$mode: $row" | tee -a "$OUT/sync-runs.txt"
        case "$mode" in
            broken) check_re "동기화 실행 결과($mode)" '^(FAILED|PARTIAL)|^[A-Z]+[[:space:]]+[0-9]+[[:space:]]+[1-9]' "$row" ;;
            *) check_re "동기화가 FAILED로 끝나고 error가 남았다($mode)" '^FAILED[[:space:]]+[0-9]+[[:space:]]+[0-9]+[[:space:]]+.+' "$row" ;;
        esac
        check_re "호출 수가 장부에 남았다($mode)" '^[A-Z]+[[:space:]]+[1-9]' "$row"
        check "공연 수 유지($mode)" "$before" "$(count_perf)"
        check "공연 조회 200($mode)" 200 "$(curl -s -o /dev/null -w '%{http_code}' "$gw/api/v1/performances")"
        ta=$(enter_token "$sid" "$a")
        check_re "대기열·입장 정상($mode)" '^.+$' "$ta"
        o=$(purchase "$sid" "$a" "$ta" "${seats[$seat]}"); seat=$((seat + 1))
        [ -n "$o" ] && [[ "$o" != *:* ]] && ok "점유·주문·결제 요청 정상($mode)" || bad "예매 경로 실패($mode): $o"
    done
    say "복구"
    stub ok > /dev/null
    row=$(run_sync 90)
    check_re "복구 뒤 동기화가 끝났다" '^(SUCCESS|PARTIAL)' "$row"
    [ "$(count_perf)" -ge "$before" ] && ok "복구 뒤 공연 수 $(count_perf) >= $before" || bad "복구 뒤 공연 수가 줄었다: $before → $(count_perf)"
    exit_status
}
