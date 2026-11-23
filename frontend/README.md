# frontend

React + Vite + TypeScript. 개발 서버는 `npm run dev`(`/api`를 gateway로 프록시, `GATEWAY_URL`로 바꾼다), 검사는 `npm run lint`, `npm test`, `npm run build`.

## 브라우저 E2E와 렌더링 측정

`e2e/`에 Playwright 시험이 있다. 의존성은 `e2e/package.json`에 따로 두고 앱 빌드와 섞지 않는다. 호스트에 브라우저를 깔지 않고 Playwright 이미지(`@playwright/test`와 같은 1.55)로 돌린다. CI에서는 `E2E` 워크플로(dev push, main 대상 PR, 수동 실행)가 키 없는 `.env.example` 기본값으로 compose를 띄워 돌린다. 실패하면 Playwright 리포트와 compose 로그가 artifact로 남는다.

로컬 실행은 `KOPIS_API_KEY`와 `ANTHROPIC_API_KEY`가 빈 환경(fixture, Mock 모드)이 전제다. KOPIS 키를 넣은 compose에서는 fixture 공연(PF305720)이 없어 실패한다. compose를 띄운 뒤(다른 프로젝트와 같이 쓰면 `FRONTEND_HOST_PORT`를 나눈다) 실행한다.

```
docker compose up -d --build --wait
FRONTEND_URL=http://localhost:5173 frontend/e2e/run.sh          # E2E
FRONTEND_URL=http://localhost:5173 frontend/e2e/run.sh bench    # 1만 석 렌더링 측정
```

- E2E는 fixture 공연 PF305720을 등록하고 판매 오픈이 8초 뒤인 회차를 만든 다음, 가입 → 로그인 → 공연 검색 → 대기열 → 좌석 선택·점유 → 결제 → 예매 내역 취소를 브라우저로 지난다. 일반 사용자에게 관리 메뉴가 보이지 않는지, 관리자가 화면에서 회차를 등록할 수 있는지도 확인한다. 관리자 계정은 `ADMIN_EMAIL`, `ADMIN_PASSWORD`(기본은 `.env.example` 값).
- 측정은 API를 route mock으로 대체하고 프로덕션 build를 서빙하는 frontend 컨테이너만 쓴다. 100x100 격자(셀 14px), 점유 2000·판매 3000, 1280x800, 새 컨텍스트 `RUNS`회(기본 10)마다 canvas 전체 그리기, 3초 스크롤의 프레임 간격, 클릭부터 재그리기 완료까지를 재서 p50·p95·max를 출력한다. 시간에 민감하므로 다른 Docker 작업이 없을 때 돌린다.
