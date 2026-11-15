#!/bin/bash
# 장애 격리 검증(NFR-8~11). 사용법:
#   perf/fault/run.sh <redis|pg|aux|kopis|dlt> [프로젝트 이름]
# compose 프로젝트(기본 fault)를 직접 띄우고 장애를 주입한다. 다른 프로젝트나 호스트 서비스에는 손대지 않는다.
# 측정이 아니라 동작 확인이라도 CPU를 쓰므로 flock /tmp/curtaincall-bench.lock 안에서 실행한다.
# 결과는 perf/fault/results/<시나리오>/ 에 남는다(커밋하지 않는다).
set -euo pipefail

SCENARIO="${1:?redis | pg | aux | kopis | dlt}"
PROJECT="${2:-fault}"
cd "$(dirname "$0")/../.."
OUT="perf/fault/results/${SCENARIO}"
mkdir -p "$OUT"
# shellcheck source=perf/fault/lib.sh
source perf/fault/lib.sh

{
    echo "commit:   $(git rev-parse HEAD)"
    echo "scenario: $SCENARIO  project: $PROJECT"
    echo "host:     $(nproc) cpu, $(free -m | awk '/Mem:/{print $2" MB total, "$7" MB available"}')"
    echo "load avg: $(cut -d' ' -f1-3 /proc/loadavg)"
    echo "containers:"; docker ps --format '  {{.Names}}'
} | tee "$OUT/env.txt"

case "$SCENARIO" in
    redis) scenario_redis ;;
    pg) scenario_pg ;;
    aux) scenario_aux ;;
    kopis) scenario_kopis ;;
    dlt) scenario_dlt ;;
    *) echo "알 수 없는 시나리오: $SCENARIO" >&2; exit 2 ;;
esac 2>&1 | tee "$OUT/result.txt"
