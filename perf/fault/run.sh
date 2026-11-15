#!/bin/bash
# 장애 격리 검증(NFR-8~11). 사용법:
#   perf/fault/run.sh <dlt|aux|redis|pg|kopis|all> [프로젝트 이름]
# 주입 순서는 dlt → aux → redis → pg → kopis(all은 이 순서). 시나리오마다 PASS/FAIL을 results/<시나리오>/verdict에 남기고,
# 하나라도 PASS가 아니거나 verdict가 없으면(중간에 죽은 경우 포함) 종료 코드가 0이 아니다.
# compose 프로젝트(기본 fault)를 직접 띄우고 장애를 주입한다. 다른 프로젝트나 호스트 서비스에는 손대지 않는다.
# 측정이 아니라 동작 확인이라도 CPU를 쓰므로 /tmp/curtaincall-bench.lock을 직접 잡는다. 다른 세션이 쥐고 있으면 바로 끝난다.
# 기준값 출처: 진입 p95 200ms는 NFR-5(spec/requirements.md), 그 밖의 API는 1000ms. 점유 p95 300ms(NFR-6)는 이 스크립트에 점유 프로브가 없어 쓰지 않는다.
# 결과는 perf/fault/results/<시나리오>/ 에 남는다(커밋하지 않는다).
# 수정 전 동작 재현: 수정 전 커밋을 체크아웃한 worktree에 perf/fault/를 복사해 dlt를 돌리면 토픽 목록(topics-after.txt)과
# DLT 발행 실패 로그(*-dlt-failures.txt)가 증거로 남고 FAIL이 된다.
set -uo pipefail

ARG="${1:?dlt | aux | redis | pg | kopis | all}"
PROJECT="${2:-fault}"
cd "$(dirname "$0")/../.."
exec 9>/tmp/curtaincall-bench.lock
# LOCK_WAIT_S초까지 락이 풀리기를 기다린다(기본 0: 바로 포기)
flock -w "${LOCK_WAIT_S:-0}" 9 || { echo "측정 락을 다른 세션이 쥐고 있다." >&2; exit 2; }
# shellcheck source=perf/fault/lib.sh
source perf/fault/lib.sh

run_one() {
    local SCENARIO="$1" OUT="perf/fault/results/$1"
    rm -rf "$OUT"; mkdir -p "$OUT"
    {
        echo "commit:   $(git rev-parse HEAD)"
        echo "scenario: $SCENARIO  project: $PROJECT"
        echo "host:     $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}')"
        echo "load avg: $(cut -d' ' -f1-3 /proc/loadavg)"
        echo "containers:"; docker ps --format '  {{.Names}} {{.Image}}'
    } | tee "$OUT/env.txt"
    case "$SCENARIO" in
        dlt) scenario_dlt ;;
        aux) scenario_aux ;;
        redis) scenario_redis ;;
        pg) scenario_pg ;;
        kopis) scenario_kopis ;;
    esac 2>&1 | tee "$OUT/result.txt"
    restore_all
    local verdict; verdict=$(cat "$OUT/verdict" 2>/dev/null || echo "없음(중간에 종료)")
    echo "[$SCENARIO] $verdict" | tee -a perf/fault/results/summary.txt
    [ "$verdict" = PASS ]
}

case "$ARG" in
    all) LIST="dlt aux redis pg kopis" ;;
    dlt|aux|redis|pg|kopis) LIST="$ARG" ;;
    *) echo "알 수 없는 시나리오: $ARG" >&2; exit 2 ;;
esac
mkdir -p perf/fault/results; : > perf/fault/results/summary.txt
RC=0
for s in $LIST; do run_one "$s" || RC=1; done
exit "$RC"
