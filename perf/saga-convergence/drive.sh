#!/bin/sh
# reservation 컨테이너 안에서 실행한다(compose exec -T reservation sh -s). gateway를 거치지 않고 내부 포트로 직접 호출한다.
# 환경변수: FIRST(첫 사용자 ID), COUNT, SEAT_BASE(첫 좌석 번호 - 1), TOKEN(카드 토큰), PAY(1이면 결제까지), HOLD_ONLY(1이면 점유만), SESSION(기본 7)
# 사용자마다 좌석 2석을 점유하고 주문을 만든다. 출력: "<userId> <holdId> <orderId>"
BASE=http://localhost:8084
SESSION="${SESSION:-7}"
uuid() { cat /proc/sys/kernel/random/uuid; }
field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p"; }
post() { # 경로, 사용자, 본문
    curl -fsS -X POST "$BASE$1" -H 'Content-Type: application/json' -H "X-User-Id: $2" \
        -H "X-Entry-Session-Id: $SESSION" -H "Idempotency-Key: $(uuid)" -d "$3"
}
i=0
while [ "$i" -lt "$COUNT" ]; do
    user=$((FIRST + i))
    seat=$((SEAT_BASE + i * 2 + 1))
    hold=$(post "/api/v1/sessions/$SESSION/holds" "$user" "{\"seatIds\":[$seat,$((seat + 1))]}" | field holdId)
    [ -n "$hold" ] || { echo "점유 실패: user=$user seat=$seat" >&2; exit 1; }
    order=-
    if [ "${HOLD_ONLY:-0}" != 1 ]; then
        order=$(post /api/v1/orders "$user" "{\"holdId\":\"$hold\"}" | field orderId)
        [ -n "$order" ] || { echo "주문 실패: user=$user hold=$hold" >&2; exit 1; }
    fi
    if [ "${PAY:-1}" = 1 ] && [ "$order" != - ]; then
        post "/api/v1/orders/$order/payment" "$user" "{\"method\":\"CARD\",\"cardToken\":\"${TOKEN:-tok_approve}\"}" > /dev/null
    fi
    echo "$user $hold $order"
    i=$((i + 1))
done
