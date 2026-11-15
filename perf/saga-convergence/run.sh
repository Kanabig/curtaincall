#!/bin/bash
# 결제 Saga 수렴 검증과 장애 주입. 사용법:
#   perf/saga-convergence/run.sh [프로젝트 이름] [시나리오...]   (시나리오를 생략하면 전부)
#   ORDERS=20 LIMIT=900 REPEAT=3 perf/saga-convergence/run.sh w3 payment-restart
# compose 프로젝트에 mysql, kafka, mock-pg, payment, reservation이 떠 있어야 한다(없으면 올린다).
# 시나리오마다 DB를 비우고 주문을 만든 뒤 장애를 넣고, 모든 주문이 종결될 때까지 걸린 시간과
# 종료 후 정합성(verify.sql)을 기록한다. 결과는 perf/saga-convergence/results/<시나리오>/ 에 남는다(커밋하지 않는다).
# 측정 락(/tmp/curtaincall-bench.lock)을 스크립트가 직접 잡는다. 다른 세션이 쥐고 있으면 바로 끝난다.
set -uo pipefail

PROJECT="${1:-w3}"
shift || true
REPEAT="${REPEAT:-3}"        # 시나리오별 반복 횟수(간헐 실패도 실패로 센다)
ORDERS="${ORDERS:-20}"        # 시나리오당 주문 수(사용자 한 명이 좌석 2석)
LIMIT="${LIMIT:-900}"         # 수렴 대기 상한(초)
CHAOS_INQUIRY_MAX_DURATION="${CHAOS_INQUIRY_MAX_DURATION:-40s}"
export CHAOS_INQUIRY_MAX_DURATION
ALL="baseline payment-restart reservation-restart kafka-outage-before kafka-outage-mid pg-drop-response pg-inquiry-down pg-timeout seat-loss user-cancel-pg-down dlt-replay duplicates-reorder pg-outage-breaker"
SCENARIOS="${*:-$ALL}"
# pg-drop-response·pg-inquiry-down은 ORDERS가 서킷 minimumCalls(20)를 넘으면 서킷이 열려 FAILED가 섞인다. seat-loss는 지연 4초 안에 drive가 끝나야 해 40 이하
[ "$ORDERS" -le 20 ] || case " $SCENARIOS " in *" pg-drop-response "*|*" pg-inquiry-down "*) echo "ORDERS=$ORDERS: pg-drop-response·pg-inquiry-down은 20 이하로 돌린다" >&2; exit 2 ;; esac

cd "$(dirname "$0")/../.."
exec 9>/tmp/curtaincall-bench.lock
flock -n 9 || { echo "측정 락을 다른 세션이 쥐고 있다. brain의 시작 허가 뒤에 다시 실행한다." >&2; exit 2; }

DIR=perf/saga-convergence
OUT_ROOT="$DIR/results"
mkdir -p "$OUT_ROOT"
COMPOSE_FILES=(-f compose.yml)
compose() { docker compose -p "$PROJECT" "${COMPOSE_FILES[@]}" "$@"; }
sql() { compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B 2>/dev/null'; }
sql_table() { compose exec -T mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t 2>/dev/null'; }
rsv() { compose exec -T reservation "$@"; }
kafka_sh() { compose exec -T kafka "$@"; }
log() { echo "[$(date +%T)] $*"; }
FAILED=0
EXTRA_FAIL=""
trap 'echo "RENAME TABLE reservation_db.reservation_off TO reservation_db.reservation" | sql > /dev/null 2>&1' EXIT

set_faults() { # delayMs failureRate timeoutRate dropResponseRate
    compose exec -T mock-pg curl -fsS -X PUT localhost:8090/pg/admin/faults -H 'Content-Type: application/json' \
        -d "{\"delayMs\":$1,\"failureRate\":$2,\"timeoutRate\":$3,\"dropResponseRate\":$4}" > /dev/null
}
reset_faults() { compose exec -T mock-pg curl -fsS -X DELETE localhost:8090/pg/admin/faults > /dev/null; }

drive() { # 첫 사용자, 수, 첫 좌석 번호 - 1, 카드 토큰, 결제 여부 → $OUT/orders.txt에 덧붙인다
    rsv env FIRST="$1" COUNT="$2" SEAT_BASE="$3" TOKEN="$4" PAY="$5" HOLD_ONLY="${HOLD_ONLY:-0}" sh -s < "$DIR/drive.sh" | tee -a "$OUT/orders.txt" > /dev/null
}

counts() { # 주문 상태 분포를 "CONFIRMED=18 REFUNDED=2" 형태로
    echo "SELECT COALESCE(GROUP_CONCAT(CONCAT(status, '=', c) ORDER BY status SEPARATOR ' '), '-') FROM (SELECT status, COUNT(*) c FROM reservation_db.orders GROUP BY status) t" | sql
}
pending() { # 아직 끝나지 않은 주문·결제 수
    echo "SELECT (SELECT COUNT(*) FROM reservation_db.orders WHERE status IN ('PAYMENT_REQUESTED', 'REFUNDING'))
        + (SELECT COUNT(*) FROM payment_db.payment WHERE status IN ('REQUESTED', 'UNKNOWN', 'CANCELLING'))" | sql
}

wait_converged() { # 시작 시각(epoch)을 받아 수렴까지 걸린 초를 CONVERGE_S에 둔다. 상한을 넘으면 -1
    local t0="$1" now left
    CONVERGE_S=-1
    while :; do
        now=$(date +%s); left=$(pending)
        echo "$((now - t0))s pending=$left $(counts)" >> "$OUT/timeline.txt"
        if [ "$left" = 0 ]; then CONVERGE_S=$((now - t0)); return 0; fi
        [ $((now - t0)) -ge "$LIMIT" ] && return 1
        sleep 1
    done
}

start_scenario() {
    NAME="$1"; EXPECT="$2"
    OUT="$OUT_ROOT/$NAME/run-$RUN"
    rm -rf "$OUT"; mkdir -p "$OUT"
    log "=== $NAME #$RUN/$REPEAT (기대: $EXPECT)"
    COMPOSE_FILES=(-f compose.yml)
    compose up -d --wait mysql kafka mock-pg reservation > /dev/null
    # 서킷 브레이커가 payment 메모리에 있어 이전 시나리오·반복의 실패율이 이어진다. 매번 새로 띄워 초기화한다
    compose up -d --wait --force-recreate payment > /dev/null
    compose unpause mock-pg 2> /dev/null || true
    reset_faults
    echo "RENAME TABLE reservation_db.reservation_off TO reservation_db.reservation" | sql 2> /dev/null || true # dlt-replay가 중간에 죽은 경우 복구
    sql < "$DIR/seed.sql" || { echo "seed 실패"; exit 1; }
    [ "$(echo "SELECT COUNT(*) FROM reservation_db.orders" | sql)" = 0 ] || { echo "seed 뒤에도 주문이 남아 있다"; exit 1; }
    EXTRA_FAIL=""
    compose exec -T mock-pg curl -fsS -X DELETE localhost:8090/pg/admin/transactions > /dev/null
    { echo "commit:     $(git rev-parse HEAD)"
      echo "image_tag:  ${IMAGE_TAG:-local}"
      echo "scenario:   $NAME  orders=$ORDERS  limit=${LIMIT}s"
      echo "host:       $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}'), $(uname -sr)"
      echo "load avg:   $(cut -d' ' -f1-3 /proc/loadavg)"
      echo "containers:"; compose ps --format '  {{.Name}}  {{.Image}}'
      echo "top cpu processes:"; ps -eo pcpu,comm --sort=-pcpu | head -6 | sed 's/^/  /'
      docker stats --no-stream --format '  {{.Name}} cpu={{.CPUPerc}} mem={{.MemUsage}}'; } > "$OUT/env.txt"
    T_START=$(date +%s)
}

CHECKS=14 # verify.sql의 검사 행 수. 이보다 적게 나오면 mysql 오류로 보고 실패 처리한다
violations() { # verify.sql의 위반 합계. 검사 행 수가 CHECKS가 아니면 ERR
    sql < "$DIR/verify.sql" | awk -F'\t' -v n="$CHECKS" 'NF==2 && $2 ~ /^[0-9]+$/ {s += $2; c++} END {if (c == n) print s+0; else print "ERR"}'
}

save_logs() { # 시나리오·반복별 payment 로그(payment는 시나리오마다 새로 뜬다)
    compose logs --no-color payment > "$OUT/payment.log" 2>&1 || true
}

breaker_state() { # 서킷 브레이커 지표(상태, 호출 수)를 파일에 덧붙인다. 인자: 라벨
    { echo "# $1 $(date +%T)"
      compose exec -T payment curl -fs localhost:8085/actuator/prometheus 2> /dev/null | grep -E '^resilience4j_circuitbreaker_(state|calls_seconds_count|not_permitted_calls_total)' || echo "(지표 없음)"; } >> "$OUT/breaker.txt"
}

finish_scenario() { # 수렴 시작 시각을 받는다
    local t0="${1:-$T_START}" verdict=PASS violations final why=""
    wait_converged "$t0" || why="수렴 상한 ${LIMIT}s 초과"
    sleep 3
    final=$(counts)
    sql_table < "$DIR/verify.sql" > "$OUT/verify.txt"
    violations=$(violations)
    [ "$violations" = 0 ] || why="${why:+$why, }위반 $violations"
    [ "$final" = "$EXPECT" ] || why="${why:+$why, }최종 상태 $final"
    save_logs
    compose exec -T payment curl -fs localhost:8085/actuator/prometheus 2> /dev/null | grep -E '^(order|payment)_' > "$OUT/payment-metrics.txt" || true
    rsv curl -fs localhost:8084/actuator/prometheus 2> /dev/null | grep -E '^(order|payment)_' > "$OUT/reservation-metrics.txt" || true
    local cf; cf=$(echo "SELECT COUNT(*) FROM payment_db.payment WHERE status = 'CANCEL_FAILED'" | sql)
    echo "CANCEL_FAILED: $cf" >> "$OUT/verify.txt"
    [ "$cf" = 0 ] || why="${why:+$why, }CANCEL_FAILED $cf건"
    why="${why:+$why, }${EXTRA_FAIL}"; why="${why%, }"; [ -z "$why" ] || verdict="FAIL($why)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(git rev-parse --short HEAD)" "$NAME" "$RUN" "$final" "$CONVERGE_S" "$violations" "$verdict" | tee -a "$OUT_ROOT/summary.tsv"
    cat "$OUT/verify.txt"
    case "$verdict" in PASS) ;; *) FAILED=1 ;; esac
}

snapshot() { # 주문·결제·좌석 상태 스냅샷(재생 전후 비교용)
    { echo "SELECT order_id, status FROM reservation_db.orders ORDER BY order_id"
      echo "SELECT order_id, status, COALESCE(pg_tid, '-') FROM payment_db.payment ORDER BY order_id"
      echo "SELECT status, COUNT(*) FROM reservation_db.seat_inventory GROUP BY status ORDER BY status"
      echo "SELECT COUNT(*) FROM reservation_db.reservation"; } | while read -r q; do echo "$q" | sql; done
}

envelope() { # 메시지 종류, 주문 ID, payload → "키|봉투"
    printf '%s|{"eventId":"%s","eventType":"%s","occurredAt":"%s","aggregateId":"%s","payload":%s}\n' \
        "$2" "$(cat /proc/sys/kernel/random/uuid)" "$1" "$(date +%Y-%m-%dT%H:%M:%S.000%:z)" "$2" "$3"
}
produce() { # 토픽, stdin에서 "키|값" 줄
    kafka_sh /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic "$1" \
        --property parse.key=true --property 'key.separator=|' > /dev/null
}
consume_all() { # 토픽 → "키|값" 줄
    kafka_sh /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic "$1" --from-beginning \
        --timeout-ms 8000 --property print.key=true --property 'key.separator=|' 2> /dev/null
}
order_ids() { awk '{print $3}' "$OUT/orders.txt"; }

# --- 시나리오 ---------------------------------------------------------------

s_baseline() {
    start_scenario baseline "CONFIRMED=$ORDERS"
    drive 1 "$ORDERS" 0 tok_approve 1
    finish_scenario
}

s_payment-restart() {
    start_scenario payment-restart "CONFIRMED=$ORDERS"
    set_faults 3000 0 0 0
    drive 1 "$ORDERS" 0 tok_approve 1
    sleep 1
    log "payment SIGKILL"; compose kill -s KILL payment > /dev/null
    sleep 5
    log "payment 재시작"; compose up -d --wait payment > /dev/null
    finish_scenario "$(date +%s)"
}

s_reservation-restart() {
    start_scenario reservation-restart "CONFIRMED=$ORDERS"
    drive 1 "$ORDERS" 0 tok_approve 1
    # 승인 응답이 나가기 시작한 직후에 죽인다
    for _ in $(seq 1 30); do
        [ "$(echo "SELECT COUNT(*) FROM payment_db.payment WHERE status = 'APPROVED'" | sql)" -gt 0 ] && break
        sleep 0.2
    done
    log "reservation SIGKILL"; compose kill -s KILL reservation > /dev/null
    sleep 5
    log "reservation 재시작"; compose up -d --wait reservation > /dev/null
    finish_scenario "$(date +%s)"
}

s_kafka-outage() { # before: 결제 시작 전에 브로커 중단, mid: 결제 시작 직후 중단
    local when="$1"
    start_scenario "kafka-outage-$when" "CONFIRMED=$ORDERS"
    if [ "$when" = before ]; then
        log "kafka 중단"; compose stop kafka > /dev/null
        drive 1 "$ORDERS" 0 tok_approve 1
    else
        set_faults 2000 0 0 0
        drive 1 "$ORDERS" 0 tok_approve 1
        log "kafka 중단"; compose stop kafka > /dev/null
    fi
    sleep 20
    log "kafka 재시작"; compose up -d --wait kafka > /dev/null
    finish_scenario "$(date +%s)"
}

s_pg-drop-response() {
    start_scenario pg-drop-response "CONFIRMED=$ORDERS"
    set_faults 0 0 0 1.0
    drive 1 "$ORDERS" 0 tok_approve 1
    finish_scenario
}

s_pg-inquiry-down() { # 첫 거래 조회 전에 PG를 멈춰, 조회가 실패하는 구간을 반드시 지나게 한다
    start_scenario pg-inquiry-down "CONFIRMED=$ORDERS"
    COMPOSE_FILES=(-f compose.yml -f "$DIR/compose.chaos.yml")
    export CHAOS_INQUIRY_INITIAL_DELAY=15s CHAOS_INQUIRY_MAX_DURATION=10m
    compose up -d --wait --force-recreate payment > /dev/null
    set_faults 0 0 0 1.0
    drive 1 "$ORDERS" 0 tok_approve 1
    log "mock-pg 정지(거래 조회 실패)"; compose pause mock-pg > /dev/null
    sleep 40
    log "mock-pg 재개"; compose unpause mock-pg > /dev/null
    local t0; t0=$(date +%s)
    compose logs --no-color payment 2>&1 | grep -c "PG 거래 조회에 실패" > "$OUT/inquiry-failures.txt" || true
    local nf; nf=$(cat "$OUT/inquiry-failures.txt")
    [ "$nf" -ge "$ORDERS" ] || EXTRA_FAIL="조회 실패 로그 ${nf}건(기대 $ORDERS건 이상, 정지 전에 조회가 끝남)"
    unset CHAOS_INQUIRY_INITIAL_DELAY CHAOS_INQUIRY_MAX_DURATION
    finish_scenario "$t0"
    COMPOSE_FILES=(-f compose.yml)
}

s_pg-timeout() {
    start_scenario pg-timeout "FAILED=$ORDERS"
    COMPOSE_FILES=(-f compose.yml -f "$DIR/compose.chaos.yml")
    compose up -d --wait --force-recreate payment > /dev/null
    set_faults 0 0 1.0 0
    drive 1 "$ORDERS" 0 tok_approve 1
    finish_scenario
    COMPOSE_FILES=(-f compose.yml)
    compose up -d --wait --force-recreate payment > /dev/null
}

s_seat-loss() { # 승인 지연 중에 점유가 만료되고 다른 사용자가 같은 좌석을 가져간다. 지연 4초(PG read timeout 5초 안)라 ORDERS는 40 이하로 둔다
    local half=$((ORDERS / 2))
    start_scenario seat-loss "CONFIRMED=$((ORDERS - half)) REFUNDED=$half"
    set_faults 4000 0 0 0
    drive 1 "$ORDERS" 0 tok_approve 1
    [ "$(echo "SELECT COUNT(*) FROM reservation_db.orders WHERE status = 'PAYMENT_REQUESTED'" | sql)" = "$ORDERS" ] \
        || EXTRA_FAIL="만료 UPDATE 전에 승인이 끝난 주문이 있음(지연 4초 안에 drive가 끝나지 않음)"
    log "앞 $half건의 점유를 만료시키고 다른 사용자가 점유"
    echo "UPDATE reservation_db.seat_inventory SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND
          WHERE holder_id BETWEEN 1 AND $half" | sql
    HOLD_ONLY=1 drive 100001 "$half" 0 tok_approve 0 || { echo "FAIL(다른 사용자의 점유 요청 실패)"; EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }다른 사용자의 점유 요청 실패"; }
    local t0; t0=$(date +%s)
    wait_converged "$t0" || true
    local kept; kept=$(echo "SELECT COUNT(*) FROM reservation_db.seat_inventory WHERE status = 'HELD' AND holder_id > 100000" | sql)
    if [ "$kept" != $((half * 2)) ]; then echo "FAIL(환불이 남의 좌석을 건드림)"; EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }환불이 남의 좌석을 건드림"; fi
    finish_scenario "$t0"
    echo "타 사용자 점유 좌석 유지: $kept (기대 $((half * 2)))" | tee -a "$OUT/verify.txt"
}

s_user-cancel-pg-down() { # 취소 중 PG 장애: 환불이 끝나기 전에 좌석을 풀지 않는다
    start_scenario user-cancel-pg-down "CONFIRMED=$ORDERS"
    drive 1 "$ORDERS" 0 tok_approve 1
    wait_converged "$(date +%s)" || true
    log "mock-pg 정지 후 사용자 취소"; compose pause mock-pg > /dev/null
    : > "$OUT/cancel-failed.txt"
    echo "SELECT reservation_id, user_id FROM reservation_db.reservation" | sql | while read -r rid uid; do
        rsv curl -fsS -o /dev/null -X POST "localhost:8084/api/v1/reservations/$rid/cancel" -H "X-User-Id: $uid" \
            -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" < /dev/null || echo "취소 요청 실패: $rid" | tee -a "$OUT/cancel-failed.txt"
    done
    [ ! -s "$OUT/cancel-failed.txt" ] || { echo "FAIL(취소 요청 거절 $(wc -l < "$OUT/cancel-failed.txt")건)"; EXTRA_FAIL="취소 요청 거절 $(wc -l < "$OUT/cancel-failed.txt")건"; }
    sleep 20
    local sold; sold=$(echo "SELECT COUNT(*) FROM reservation_db.seat_inventory WHERE status = 'SOLD'" | sql)
    echo "PG 정지 중 SOLD 좌석: $sold (기대 $((ORDERS * 2)))" | tee "$OUT/during-outage.txt"
    [ "$sold" = $((ORDERS * 2)) ] || { echo "FAIL(환불 전에 좌석이 풀림)"; EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }환불 전에 좌석이 풀림($sold)"; }
    log "mock-pg 재개"; compose unpause mock-pg > /dev/null
    local t0; t0=$(date +%s)
    # 예매가 CANCELLED가 될 때까지(환불 완료) 기다린다
    CONVERGE_S=-1
    while [ $(( $(date +%s) - t0 )) -lt "$LIMIT" ]; do
        left=$(echo "SELECT COUNT(*) FROM reservation_db.reservation WHERE status <> 'CANCELLED'" | sql)
        echo "$(( $(date +%s) - t0 ))s 미취소 예매=$left" >> "$OUT/timeline.txt"
        [ "$left" = 0 ] && { CONVERGE_S=$(( $(date +%s) - t0 )); break; }
        sleep 1
    done
    # 같은 취소를 한 번 더 보내도 두 번 환불되지 않는다
    echo "SELECT reservation_id, user_id FROM reservation_db.reservation LIMIT 3" | sql | while read -r rid uid; do
        rsv curl -s -o /dev/null -X POST "localhost:8084/api/v1/reservations/$rid/cancel" -H "X-User-Id: $uid" \
            -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" < /dev/null
    done
    sleep 5
    local cmds; cmds=$(kafka_sh /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic payment.command --from-beginning --timeout-ms 8000 2> /dev/null | grep USER_CANCEL | grep -cF -f <(order_ids) || true)
    echo "USER_CANCEL 명령 수: $cmds (기대 $ORDERS, 같은 취소를 다시 보내도 환불 명령이 늘지 않아야 한다)" | tee -a "$OUT/during-outage.txt"
    local avail; avail=$(echo "SELECT COUNT(*) FROM reservation_db.seat_inventory WHERE status = 'AVAILABLE'" | sql)
    echo "취소 뒤 AVAILABLE 좌석: $avail (기대 2000)" | tee -a "$OUT/during-outage.txt"
    local why="$EXTRA_FAIL"
    add() { why="${why:+$why, }$1"; }
    [ "$CONVERGE_S" -ge 0 ] || add "취소 수렴 상한 초과"
    [ "$avail" = 2000 ] || add "좌석 복구 $avail"
    [ "$cmds" = "$ORDERS" ] || add "환불 명령 $cmds건, 기대 $ORDERS"
    [ "$(counts)" = "CONFIRMED=$ORDERS" ] || add "주문 상태 $(counts)"
    local cf; cf=$(echo "SELECT COUNT(*) FROM payment_db.payment WHERE status = 'CANCEL_FAILED'" | sql)
    [ "$cf" = 0 ] || add "CANCEL_FAILED $cf건"
    save_logs
    sql_table < "$DIR/verify.sql" > "$OUT/verify.txt"
    echo "CANCEL_FAILED: $cf" >> "$OUT/verify.txt"
    local v; v=$(violations)
    [ "$v" = 0 ] || add "위반 $v"
    local verdict=PASS; [ -z "$why" ] || verdict="FAIL($why)"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$(git rev-parse --short HEAD)" "$NAME" "$RUN" "$(counts)" "$CONVERGE_S" "$v" "$verdict" | tee -a "$OUT_ROOT/summary.tsv"
    [ "$verdict" = PASS ] || FAILED=1
}

s_dlt-replay() { # 소비가 실패해 DLT로 간 응답을 재투입해 수렴시킨다. 재전송(2분)이 아니라 재투입으로 수렴했음을 시나리오 순서로 보장한다
    start_scenario dlt-replay "CONFIRMED=$ORDERS"
    local resend_s=120 topic="" tries=0 base cmd_base
    dlt_topics() { kafka_sh /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list 2> /dev/null | grep -E 'payment\.payment.*DLT' || true; }
    dlt_count() { # payment.payment DLT 메시지 수(카프카에서 직접 센다)
        kafka_sh /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --include 'payment\.payment.*DLT' \
            --from-beginning --timeout-ms 4000 2> /dev/null | wc -l
    }
    approve_count() { consume_all payment.command | grep -c ApprovePayment || true; }
    base=$(dlt_count)             # 지난 회차에 쌓인 DLT 메시지는 이번 회차 증거가 아니다
    cmd_base=$(approve_count)
    echo "RENAME TABLE reservation_db.reservation TO reservation_db.reservation_off" | sql
    local t_first; t_first=$(date +%s) # 첫 ApprovePayment 시각(drive 시작)
    drive 1 "$ORDERS" 0 tok_approve 1
    # 1. 주문 수만큼 DLT에 쌓일 때까지 기다린다. 쌓이는 즉시 테이블을 복구하고 재투입한다
    while [ "$tries" -lt 30 ]; do
        [ $(( $(dlt_count) - base )) -ge "$ORDERS" ] && { topic=$(dlt_topics | head -1); break; }
        tries=$((tries + 1))
    done
    echo "RENAME TABLE reservation_db.reservation_off TO reservation_db.reservation" | sql
    local t_replay; t_replay=$(date +%s)
    {
        echo "DLT 토픽: ${topic:-없음}, 시작 전 $base건, 지금 $(dlt_count)건 (기대 증가 $ORDERS건 이상)"
        echo "reservation 로그의 테이블 없음 오류: $(compose logs --no-color reservation 2>&1 | grep -ci "reservation_db.reservation' doesn't exist")건"
        echo "첫 ApprovePayment부터 재투입까지: $((t_replay - t_first))s (재전송 간격 ${resend_s}s)"
    } | tee "$OUT/dlt.txt"
    if [ -z "$topic" ]; then
        echo "FAIL(DLT에 주문 수만큼 안 쌓임)"; EXTRA_FAIL="DLT에 주문 수만큼 안 쌓임(재투입 못 함)"; finish_scenario; return
    fi
    # 2. 재전송 간격보다 먼저 재투입했는지
    [ $((t_replay - t_first)) -lt "$resend_s" ] || EXTRA_FAIL="재투입이 재전송 간격 뒤($((t_replay - t_first))s)"
    local replay; replay=$(rsv curl -fsS -X POST "localhost:8084/actuator/dlt/$topic" -H 'Content-Type: application/json' -d '{}')
    echo "$replay" | tee -a "$OUT/dlt.txt"
    echo "$replay" | grep -Eq '"replayed" *: *[1-9]' || EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }재투입 0건(DLT 응답: $replay)"
    wait_converged "$t_replay" || true
    echo "재투입부터 수렴까지: ${CONVERGE_S}s" | tee -a "$OUT/dlt.txt"
    # 3. 재투입부터 확정까지 ApprovePayment가 다시 나가지 않았는지(주문당 1건)
    local sent=$(( $(approve_count) - cmd_base ))
    echo "ApprovePayment 발행: $sent건 (기대 $ORDERS건, 재전송 0)" | tee -a "$OUT/dlt.txt"
    [ "$sent" = "$ORDERS" ] || EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }ApprovePayment 재전송($sent건, 기대 $ORDERS건)"
    finish_scenario "$t_replay"
}

s_duplicates-reorder() { # 같은 응답 중복, 새 이벤트 ID로 다시 보낸 응답, 순서가 바뀐 응답, 같은 명령 재전송
    start_scenario duplicates-reorder "CONFIRMED=$ORDERS"
    set_faults 4000 0 0 0
    drive 1 "$ORDERS" 0 tok_approve 1
    log "승인보다 먼저 PaymentCancelled 주입(순서 뒤바뀜)"
    for o in $(order_ids); do
        envelope PaymentCancelled "$o" "{\"paymentId\":\"pay_forged\",\"orderId\":\"$o\",\"amount\":220000,\"reason\":\"SEAT_LOST\"}"
    done | produce payment.payment
    wait_converged "$(date +%s)" || true
    sleep 5
    snapshot > "$OUT/before.txt"
    consume_all payment.payment | grep -F -f <(order_ids) > "$OUT/captured.txt" || true
    if [ "$(wc -l < "$OUT/captured.txt")" -lt "$ORDERS" ]; then EXTRA_FAIL="캡처한 응답이 주문 수보다 적어 재생이 공허함"; fi
    log "캡처한 응답 $(wc -l < "$OUT/captured.txt")건: 같은 ID로 두 번, 새 ID로 역순, 모순 응답, 명령 재전송"
    produce payment.payment < "$OUT/captured.txt"
    tac "$OUT/captured.txt" | while IFS='|' read -r key value; do
        echo "$key|$(echo "$value" | sed "s/\"eventId\":\"[^\"]*\"/\"eventId\":\"$(cat /proc/sys/kernel/random/uuid)\"/")"
    done | produce payment.payment
    for o in $(order_ids); do
        envelope PaymentFailed "$o" "{\"paymentId\":\"pay_forged\",\"orderId\":\"$o\",\"reason\":\"DECLINED\"}"
        envelope PaymentCancelled "$o" "{\"paymentId\":\"pay_forged\",\"orderId\":\"$o\",\"amount\":220000,\"reason\":\"SEAT_LOST\"}"
    done | produce payment.payment
    for o in $(order_ids); do
        envelope ApprovePayment "$o" "{\"orderId\":\"$o\",\"userId\":1,\"amount\":220000,\"cardToken\":\"tok_approve\"}"
    done | produce payment.command
    sleep 20
    snapshot > "$OUT/after.txt"
    if diff -q "$OUT/before.txt" "$OUT/after.txt" > /dev/null; then echo "재생 전후 상태 동일" | tee "$OUT/replay-diff.txt"
    else diff "$OUT/before.txt" "$OUT/after.txt" | tee "$OUT/replay-diff.txt"; echo "FAIL(재생으로 상태가 바뀜)"; EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }재생으로 상태가 바뀜"; fi
    finish_scenario "$(date +%s)"
}

# 응답 유실도 서킷 실패율에 들어가므로, 유실이 몰리면 이후 승인은 PG를 호출하지 않고 안전하게 실패한다(설계 의도).
# 서킷: 최근 20건 중 50% 이상 실패면 10초 열림(window 20, minimumCalls 20). 열림 중 주문은 PG에 보내지 않고 FAILED, 좌석 해제.
# 열림 시간이 지나면 half-open(3건)을 거쳐 닫히고 새 주문은 CONFIRMED.
s_pg-outage-breaker() {
    start_scenario pg-outage-breaker "CONFIRMED=26 FAILED=10"
    set_faults 0 0 0 1.0
    drive 1 20 0 tok_approve 1
    local i=0 open=0
    while [ "$i" -lt 30 ]; do # 승인 20건이 끝나 서킷이 열릴 때까지
        compose exec -T payment curl -fs localhost:8085/actuator/prometheus 2> /dev/null \
            | grep -E '^resilience4j_circuitbreaker_state.*name="pg-approval".*state="open".* 1\.0' > /dev/null && { open=1; break; }
        i=$((i + 1)); sleep 1
    done
    breaker_state "유실 20건 뒤(열림 대기 ${i}s)"
    [ "$open" = 1 ] || { echo "FAIL(서킷이 열리지 않음)" | tee -a "$OUT/breaker.txt"; EXTRA_FAIL="서킷이 열리지 않음"; }
    log "서킷 열림 중 주문 10건"
    drive 21 10 40 tok_approve 1
    sleep 3
    local failed freed notsent
    failed=$(echo "SELECT COUNT(*) FROM reservation_db.orders WHERE status = 'FAILED'" | sql)
    freed=$(echo "SELECT COUNT(*) FROM reservation_db.seat_inventory WHERE seat_id BETWEEN 41 AND 60 AND status = 'AVAILABLE'" | sql)
    notsent=$(compose logs --no-color payment 2>&1 | grep -c "reason=CIRCUIT_OPEN")
    { echo "열림 중 FAILED 주문: $failed (기대 10)"; echo "해제된 좌석(41~60): $freed (기대 20)"; echo "PG에 보내지 않은 승인(CIRCUIT_OPEN 로그): $notsent (기대 10)"; } | tee -a "$OUT/breaker.txt"
    [ "$failed" = 10 ] && [ "$freed" = 20 ] && [ "$notsent" = 10 ] || { echo "FAIL(열림 중 처리 기대와 다름)" | tee -a "$OUT/breaker.txt"; EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }열림 중 처리 기대와 다름"; }
    breaker_state "열림 중 주문 뒤"
    log "열림 시간 경과 대기, PG 유실 해제"
    sleep 11
    reset_faults
    drive 31 3 60 tok_approve 1 # half-open 허용 3건
    wait_converged "$(date +%s)" || true
    breaker_state "half-open 3건 뒤"
    drive 34 3 66 tok_approve 1
    wait_converged "$(date +%s)" || true
    breaker_state "닫힌 뒤 3건 뒤"
    tail -n 12 "$OUT/breaker.txt" | grep -qE 'state="closed".* 1\.0' || EXTRA_FAIL="${EXTRA_FAIL:+$EXTRA_FAIL, }서킷이 닫히지 않음"
    finish_scenario "$(date +%s)"
}

for s in $SCENARIOS; do
    for RUN in $(seq 1 "$REPEAT"); do
        case "$s" in
            kafka-outage-before) s_kafka-outage before ;;
            kafka-outage-mid) s_kafka-outage mid ;;
            *) "s_$s" ;;
        esac
    done
done
reset_faults
log "끝. 요약: $OUT_ROOT/summary.tsv"
column -t -s$'\t' "$OUT_ROOT/summary.tsv" 2> /dev/null || cat "$OUT_ROOT/summary.tsv"
exit "$FAILED"
