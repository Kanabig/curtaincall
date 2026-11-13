# curtaincall

공연 예매처 백엔드. 판매 오픈 시각의 트래픽 몰림, 좌석 단위 경쟁, 결제 실패 보상을 MSA 구조에서 다룬다.

> 진행 중

## 기술 스택

| 구분 | 사용 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 3.5.16, Spring Cloud 2025.0.x |
| 빌드 | Gradle 8.14.7, 멀티모듈 |
| 저장소·메시징 | MySQL, Redis, Kafka |
| 프론트 | React, Vite, TypeScript |
| 실행 환경 | Docker Compose, GitHub Actions |

## 개발 환경

- JDK 21, `./gradlew build`로 전체 빌드(spotlessCheck, checkstyle, 테스트 포함). 포맷은 `./gradlew spotlessApply`로 맞춘다.
- IntelliJ: palantir-java-format 플러그인을 설치하고 Enable 해야 에디터 포맷이 spotless와 같아진다.
- 구조: `services/*`(서비스), `libs/*`(공통 모듈), `build-logic`(Gradle 컨벤션 플러그인).

## 기술적 의사결정

**예매처 시뮬레이션.** curtaincall은 기획사와 판매 대행 계약을 맺은 예매처라고 가정한다. 공연·공연장 정보는 KOPIS(공연예술통합전산망) 오픈 API에서 동기화하고, 원래 계약으로 받는 데이터(좌석 배치, 등급·가격, 회차, 판매 오픈 시각)는 객석 수 기준으로 생성한다. 좌석과 판매 데이터는 시연용 생성 데이터다.

**좌석 모델.** 좌석은 층·구역·열·번호·좌표를 갖고, 공연장 배치와 회차별 좌석 재고를 분리한다. 무대에 가까울수록 등급이 높아 앞열에 요청이 몰리는 실제 분포를 부하 테스트에서 재현할 수 있다.

**MSA, 모노레포.** gateway, user, event, queue, reservation, payment, notification, search-assist로 나누고 서비스별 DB 스키마를 쓴다. 대기열은 트래픽 특성과 확장 단위가, 검색 보조는 외부 의존·지연 특성이 달라 분리했다. 이 규모만 보면 모듈러 모놀리스가 더 맞는 선택이다. 그래도 서비스 간 일관성(Outbox, Saga)과 장애 격리를 직접 다뤄 보려고 MSA를 택했다. 분리 효과는 대기열 폴링, PG 지연, 부가 서비스 장애가 예매 API에 주는 영향을 측정해 확인한다. 한 PC에서 실행하므로 독립 배포·확장 효과는 측정하지 않는다. 저장소는 하나로 두고 Gradle 멀티모듈로 나눈다.

**Spring Boot 3.5에서 시작해 4.1로.** 현업에 가장 많이 깔린 3.x로 개발하고 메이저 버전업을 직접 진행한다. 3.5는 2026-06-30에 OSS 지원이 끝났고 Spring Initializr도 4.0 미만 생성을 막아, 4.1.2로 생성한 뒤 3.5.16과 Gradle 8.14.7(3.5 지원 범위)로 내렸다.

**AI는 예매 경로 밖에.** 자연어 검색어를 구조화된 검색 조건으로 바꾸는 데만 LLM을 쓴다. 대기열·좌석·결제에는 넣지 않는다. 출력은 서버에서 검증하고, 실패하거나 느리면 키워드 검색으로 fallback한다. API 키 없이 Mock 모드로 실행된다.

## 작업 방식

이슈는 epic(서비스·기능군) > story(기능 ID 또는 사용자 화면 단위 요구사항, 수용 기준 포함) / task(환경·성능·검증) / bug로 나누고, epic 아래에 하위 이슈로 연결한다. 이슈에는 유형과 서비스 라벨만 붙이고, 변경의 성격(type)은 PR 제목과 PR 라벨로 정한다. story 하나가 브랜치 `<type>/<이슈번호>-<설명>`과 PR 하나에 대응한다. 이슈 브랜치는 `dev`에서 나와 `dev`로 squash merge한다. 마일스톤이 끝나면 `dev`를 `main`에 merge commit으로 올리고 태그를 붙인다. `main`은 항상 릴리스 가능한 상태다. PR 제목은 Conventional Commits를 따른다. 커밋 템플릿은 `git config commit.template .gitmessage.txt`로 쓴다.

| 이슈 유형 | 주로 쓰는 PR type |
|---|---|
| story | feat |
| task | build, ci, perf, test, refactor, chore, docs (새 기능을 추가하는 작업은 feat) |
| bug | fix |

## 데이터 출처
공연·공연장 정보: (재)예술경영지원센터 공연예술통합전산망(www.kopis.or.kr)
