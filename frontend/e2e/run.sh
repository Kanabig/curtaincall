#!/bin/bash
# compose 위에서 E2E나 렌더링 측정을 돌린다. 호스트에 브라우저가 없어 Playwright 이미지(@playwright/test와 같은 버전)를 쓴다.
#   frontend/e2e/run.sh            E2E (Playwright 인자를 뒤에 붙일 수 있다)
#   frontend/e2e/run.sh bench      1만 석 렌더링 측정
# 환경 변수: FRONTEND_URL(기본 http://localhost:5173), ADMIN_EMAIL, ADMIN_PASSWORD, RUNS(측정 반복, 기본 10)
set -euo pipefail
cd "$(dirname "$0")"
IMAGE="mcr.microsoft.com/playwright:v1.55.0-noble"
if [ "${1:-}" = bench ]; then CMD='npm ci --no-audit --no-fund --silent && node render-bench.mjs'; else CMD='npm ci --no-audit --no-fund --silent && npx playwright test "$@"'; fi
docker run --rm --network host --ipc=host --user "$(id -u):$(id -g)" -e HOME=/tmp \
    -e BASE_URL="${FRONTEND_URL:-http://localhost:5173}" -e RUNS="${RUNS:-10}" -e GIT_SHA="$(git rev-parse --short HEAD)" \
    -e ADMIN_EMAIL="${ADMIN_EMAIL:-admin@curtaincall.local}" -e ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin-local-password}" \
    -v "$PWD":/work -w /work "$IMAGE" \
    bash -c "$CMD" -- "$@"
