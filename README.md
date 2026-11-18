# curtaincall

공연 예매처 백엔드. 판매 대행 계약을 맺은 가상의 예매처를 전제로, 판매 오픈 시각에 몰리는 트래픽과 좌석 경쟁, 결제 실패 처리를 MSA 구조에서 다룬다.

- 1만 명이 동시에 들어오는 판매 오픈을 Redis 대기열과 입장 토큰으로 받는다
- 좌석 점유는 MySQL 조건부 UPDATE로 처리하고, 부하 중 초과 판매 0을 검증한다
- 결제는 오케스트레이션 Saga와 Outbox로 묶고, PG·Redis·외부 API 장애가 예매 경로로 번지지 않게 격리한다

서비스는 gateway, user, event, queue, reservation, payment, notification, search-assist로 나뉘고 서비스마다 DB 스키마를 따로 쓴다. 결제는 가짜 PG(mock-pg)와 연동한다.

## 기술 스택

| 구분 | 사용 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1, Spring Cloud 2025.1 |
| 저장소·메시징 | MySQL 8.4, Redis 8.0, Kafka 4.3 |
| 프론트 | React 19, Vite, TypeScript |
| 관측 | Prometheus, Grafana |
| 빌드·실행 | Gradle 멀티모듈, Docker Compose, GitHub Actions, GHCR |
| 테스트 | JUnit, Testcontainers, k6, Playwright |

## 개발 환경

- JDK 21. `./gradlew build`로 전체 빌드와 테스트를 돌린다(spotlessCheck, checkstyle 포함). 포맷은 `./gradlew spotlessApply`.
- IntelliJ는 palantir-java-format 플러그인을 켜야 에디터 포맷이 spotless와 같아진다.
- 구조: `services/*` 서비스, `libs/*` 공통 모듈, `build-logic` Gradle 컨벤션 플러그인, `frontend`, `perf/*` 부하·장애 측정 스크립트.

## 실행

```bash
cp .env.example .env
docker compose up -d --build --wait
```

- 브라우저에서 http://localhost:5173 을 연다.
- 메일은 외부로 나가지 않고 Mailpit(http://localhost:8025)에 쌓인다.
- 서비스 지표는 Grafana(http://localhost:3000, admin / `.env`의 `GRAFANA_ADMIN_PASSWORD`)의 "서비스 공통 지표" 대시보드에서 본다.
- KOPIS·LLM API 키 없이 뜬다. 키가 없으면 공연 정보는 fixture, 검색 보조는 Mock으로 동작한다.
- 빌드 없이 띄우려면 `.env`에 `IMAGE_REGISTRY=ghcr.io/kanabig/curtaincall`, `IMAGE_TAG=latest`를 넣고 `docker compose up -d --no-build --wait`.

## 데이터 출처

- 공연·공연장 정보: (재)예술경영지원센터 공연예술통합전산망(www.kopis.or.kr)
- 좌석 배치, 등급·가격, 회차, 판매 오픈 시각은 객석 수를 기준으로 만든 시연용 데이터다.

## 알려진 한계

- 부하·장애 측정은 PC 한 대에서 모든 서비스와 부하 생성기를 함께 띄워 했다. gateway를 여러 대로 늘린 측정은 하지 않았고, 이 조건에서 대기열 진입 p95는 목표(200ms)에 못 미친다.
- Redis가 응답하지 않으면 속도 제한은 막지 않고 통과시킨다(fail-open). 예매를 멈추지 않기 위한 선택이다.
- PG 장애 중에 들어온 결제는 거래 조회를 거쳐 실패로 확정되기까지 최대 수십 분이 걸린다.
