# gonza-payment

포인트 충전 / 기프티콘 구매 / 기프티콘 사용 시스템 — **결제 정합성과 의존 격리**를 코드와 부하 테스트로 검증하는 프로젝트.

## 핵심 특징

- **3-Phase 결제 처리** — PG 외부 호출(IO)을 DB 트랜잭션 바깥으로 분리. 외부 응답 지연이 DB 락을 점유하지 않음
- **멱등성** — `Idempotency-Key` 헤더 + `(userId, idempotencyKey)` unique 제약으로 중복 결제 차단
- **조건부 UPDATE 기반 동시성 제어** — `WHERE balance >= :amount`, `WHERE status = 'ISSUED'` 로 락 없이 race condition 방어
- **k6 + Docker Compose 시나리오 테스트** — 부하 / 중복결제 / 잔액 경합 / 동시 사용 4종을 자동 실행 + HTML 리포트
- **의존 폭발 시뮬레이션** — 알림 채널 6개를 동기 직렬로 호출하여 의존 폭발이 응답 시간에 미치는 영향을 부하 테스트로 측정 (자세한 내용은 [의존 격리 시뮬레이션](#의존-격리-시뮬레이션) 참고)

## 기술 스택

- **Language**: Kotlin
- **Framework**: Spring Boot 3.3
- **Persistence**: Spring Data JPA + PostgreSQL
- **Test**: JUnit 5 + Mockito + H2 + Testcontainers
- **Load Test**: k6 + Docker Compose
- **Infra**: Docker Compose

## 실행

### Docker Compose

```bash
docker compose up --build
```

`http://localhost:8080` 에서 API 서버가 실행됩니다.

### 테스트

```bash
# 단위 + 통합 + API 테스트 (H2 인메모리 DB)
./gradlew test

# 동시성 테스트 (Testcontainers - Docker 필요)
./gradlew testWithDocker
```

#### 테스트 구성

| 레벨 | 명령어 | DB | 대상 |
|------|--------|-----|------|
| 단위 테스트 | `./gradlew test` | H2 | 서비스 로직 (Mockito) |
| 통합 테스트 | `./gradlew test` | H2 | Repository, Controller (MockMvc) |
| 동시성 테스트 | `./gradlew testWithDocker` | PostgreSQL (Testcontainers) | 동시 구매/사용 race condition |
| 시나리오 테스트 | `./run-test.sh <시나리오>` | PostgreSQL | 부하, 중복결제, 잔액 경합, 동시 사용 |

## API

Base path: `/api`

### 유저

| Method | Path | 설명 |
|--------|------|------|
| POST | `/users` | 유저 생성 |
| GET | `/users/{userId}/wallet` | 잔액 조회 |

### 포인트 충전

| Method | Path | 설명 |
|--------|------|------|
| POST | `/wallets/{userId}/charges` | 포인트 충전 (Idempotency-Key 헤더 필수) |

### 상품

| Method | Path | 설명 |
|--------|------|------|
| POST | `/products` | 상품 등록 |
| GET | `/products` | 상품 목록 조회 |

### 기프티콘

| Method | Path | 설명 |
|--------|------|------|
| POST | `/users/{userId}/gifticons` | 기프티콘 구매 |
| POST | `/users/{userId}/gifticons/{gifticonId}/consume` | 기프티콘 사용 |

## E2E 시나리오

```bash
# 1. 유저 생성
curl -s -X POST http://localhost:8080/api/users \
  -H 'Content-Type: application/json' \
  -d '{"name": "gonza"}'
# -> {"userId": "<USER_ID>"}

# 2. 상품 등록
curl -s -X POST http://localhost:8080/api/products \
  -H 'Content-Type: application/json' \
  -d '{"name": "스타벅스 아메리카노", "price": 4500}'
# -> {"id": "<PRODUCT_ID>", ...}

# 3. 포인트 충전
curl -s -X POST http://localhost:8080/api/wallets/<USER_ID>/charges \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: charge-001' \
  -d '{"amount": 10000}'
# -> {"chargeId": "...", "status": "COMPLETED", "balance": 10000}

# 4. 같은 키로 재호출 (멱등성 확인 - 잔액 변화 없음)
curl -s -X POST http://localhost:8080/api/wallets/<USER_ID>/charges \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: charge-001' \
  -d '{"amount": 10000}'
# -> {"chargeId": "...", "status": "COMPLETED", "balance": 10000}

# 5. 기프티콘 구매
curl -s -X POST http://localhost:8080/api/users/<USER_ID>/gifticons \
  -H 'Content-Type: application/json' \
  -d '{"productId": "<PRODUCT_ID>"}'
# -> {"gifticonId": "<GIFTICON_ID>", "code": "...", "status": "ISSUED", "balance": 5500}

# 6. 기프티콘 사용
curl -s -X POST http://localhost:8080/api/users/<USER_ID>/gifticons/<GIFTICON_ID>/consume
# -> {"gifticonId": "...", "status": "CONSUMED"}
```

## 시나리오 테스트

k6 + Docker Compose 기반 시나리오 테스트. Docker만 있으면 실행 가능.

```bash
./run-test.sh              # 사용법 출력
./run-test.sh --build load # 코드 변경 후 이미지 재빌드하여 실행
./run-test.sh clean        # 결과 + 시딩 데이터 초기화 (다음 실행 시 처음부터)
```

테스트 완료 시 브라우저에서 리포트가 자동으로 열립니다.

> 각 시나리오의 상세 흐름과 응답 패턴 분류는 `load-test/scenarios/<시나리오>/README.md` 참고

### 1. 부하 테스트 (`load`)

충전 → 구매 → 사용 전체 흐름을 다수 VU로 병렬 실행하여 처리량과 응답 시간을 측정한다.

```bash
./run-test.sh load         # 기본 100 VU
./run-test.sh load 200     # 200 VU
./run-test.sh load 1000    # 1000 VU 스트레스
```

| 인자 | 기본값 | 설명 |
|------|--------|------|
| VU 수 | 100 | 동시 가상 사용자 수 |

**부하 프로필**: Ramp-up(30s) → 절반 유지(2m) → 스파이크(30s) → 최대 유지(1m) → Ramp-down(30s)

**성공 기준**: HTTP 실패율 < 10%, p95 < 2초, p99 < 5초

### 2. 중복결제 시나리오 (`idempotency`)

동일 `Idempotency-Key`로 동시에 충전 요청을 보내 중복결제가 발생하는지 검증한다.
PG 응답 지연(2초) 중 클라이언트가 재요청하는 상황을 시뮬레이션한다.

```bash
./run-test.sh idempotency           # 기본 20 VU, 10회 반복
./run-test.sh idempotency 50 20     # 50 VU, 20회 반복
```

| 인자 | 기본값 | 설명 |
|------|--------|------|
| VU 수 | 20 | 동시 테스트 사용자 수 |
| 반복 | 10 | VU당 충전 시도 횟수 |

**테스트 흐름**: 잔액 조회 → 동일 키로 2건 동시 충전(`http.batch`) → 잔액 검증(+10,000원 정확히 1건)

**성공 기준**: 중복결제 0건, 잔액 정합성 > 95%

### 3. 잔액 경합 시나리오 (`balance-race`)

잔액이 상품 가격(4,500원)과 동일한 상태에서 동시 구매 요청 시 잔액이 음수가 되지 않는지 검증한다.
`subtractBalance`의 `WHERE balance >= :amount` 조건이 동시 부하에서도 정상 동작하는지 확인하는 회귀 테스트.

```bash
./run-test.sh balance-race           # 기본 20 VU, 10회 반복, 동시 2건
./run-test.sh balance-race 50 20     # 50 VU, 20회 반복
./run-test.sh balance-race 30 10 3   # 30 VU, 10회 반복, 동시 3건
```

| 인자 | 기본값 | 설명 |
|------|--------|------|
| VU 수 | 20 | 동시 테스트 사용자 수 |
| 반복 | 10 | VU당 구매 시도 횟수 |
| 동시 구매 | 2 | 동시에 보내는 구매 요청 수 |

**테스트 흐름**: 잔액을 4,500원으로 설정 → 동일 상품 2건 동시 구매(`http.batch`) → 잔액 >= 0 검증

**성공 기준**: 잔액 음수 0건, 초과 구매 0건, 잔액 정합성 > 95%

### 4. 동시 사용 시나리오 (`consume-race`)

동일 기프티콘에 대해 동시에 consume 요청을 보내 중복 사용이 발생하는지 검증한다.
`UPDATE gifticon SET status = 'CONSUMED' WHERE id = :id AND status = 'ISSUED'` 조건부 업데이트가 동시 부하에서도 정상 동작하는지 확인.

```bash
./run-test.sh consume-race            # 기본 20 VU, 10회 반복, 동시 2건
./run-test.sh consume-race 50 20 3    # 50 VU, 20회 반복, 동시 3건
```

| 인자 | 기본값 | 설명 |
|------|--------|------|
| VU 수 | 20 | 동시 테스트 사용자 수 |
| 반복 | 10 | VU당 시도 횟수 |
| 동시 사용 | 2 | 동시에 보내는 consume 요청 수 |

**테스트 흐름**: 기프티콘 구매(ISSUED) → 동일 기프티콘 N건 동시 consume(`http.batch`) → 정확히 1건 200, 나머지 409 검증

**성공 기준**: 중복 사용 0건, 정확히 1건만 성공

### 결과 확인

- 대시보드: http://localhost:19000
- 개별 리포트: `http://localhost:19000/<시나리오>/report.html`
- 파일: `load-test/results/<시나리오>/report.html`

## 정합성 전략

결제 정합성은 다음 4축으로 보장한다.

### 1. 3-Phase 결제 처리

PG 외부 호출(IO)을 DB 트랜잭션 바깥으로 분리하여, 외부 응답 지연이 DB 락을 점유하지 않도록 한다.

```
Phase 1 (TX) — createOrGetCharge()    : 충전 레코드 생성 (PENDING) + 멱등성 키 등록
   ↓
Phase 2 (IO) — pgClient.approve()     : PG 외부 호출 (트랜잭션 바깥, 지연/실패 허용)
   ↓
Phase 3 (TX) — completeCharge()       : 충전 상태 전이 + 잔액 가산 + 원장 기록
```

전체 흐름 다이어그램은 [`docs/architecture.md`](docs/architecture.md) 참고.

### 2. 멱등성

- `(userId, idempotencyKey)` unique constraint
- 동일 키 재호출 시 Phase 1에서 기존 레코드 조회 → 동일 결과 반환
- PG 응답 지연 중 클라이언트 재시도가 발생해도 중복 결제 X

### 3. 조건부 UPDATE 기반 동시성 제어

비관적 락 없이 DB 레벨에서 race condition 차단:

| 대상 | SQL | 보호 시나리오 |
|------|-----|---------------|
| 잔액 차감 | `UPDATE wallet SET balance = balance - :price WHERE balance >= :price` | 잔액 부족 상태에서 동시 구매 → 음수 방지 |
| 기프티콘 사용 | `UPDATE gifticon SET status = 'CONSUMED' WHERE id = :id AND status = 'ISSUED'` | 동일 기프티콘 동시 consume → 중복 사용 방지 |

### 4. 트랜잭션 단위

- **충전**: Charge + Wallet + Ledger 단일 트랜잭션
- **구매**: Wallet + Gifticon + Ledger 단일 트랜잭션

## 의존 격리 시뮬레이션

알림 발송이 결제 응답 경로에 직접 결합되어 있을 때 발생하는 문제를 의도적으로 재현한다.

### 현재 구조 — 동기 직렬 fan-out

`ChargeFacade.chargePoints()` 는 충전 완료 후 알림 채널 6개를 동기 직렬로 호출한다:

```
ChargeService.chargePoints()
   ↓
NotificationService.notify(SMS)
   ↓
NotificationService.notify(EMAIL)
   ↓
NotificationService.notify(PUSH)
   ↓
NotificationService.notify(KAKAO_ALIMTALK)
   ↓
NotificationService.notify(SLACK)
   ↓
NotificationService.notify(MARKETING_HUB)
   ↓
return ChargeResponse
```

채널별 latency가 누적되어 **API 응답 시간 = 결제 시간 + Σ(채널 latency)** 가 된다.

### 측정

`load` 시나리오로 채널 수에 따른 응답 시간 변화를 부하 테스트로 측정한다. 자세한 시나리오 분석은 [`docs/scenario/fanout-explosion/README.md`](docs/scenario/fanout-explosion/README.md) 참고.

이 구조는 향후 도메인 이벤트 + 비동기 발송으로 분리할 계획 ([Roadmap](#roadmap) 참고).

## 프로젝트 구조

```
src/main/kotlin/com/gonza/payment/
├── domain/         # JPA 엔티티, 열거형
├── repository/     # Spring Data JPA 리포지토리
├── service/        # 비즈니스 로직 (ChargeService 3-Phase 포함)
├── controller/     # REST API
├── facade/         # ChargeFacade (충전 + 알림 fan-out 조합)
├── pg/             # PG Mock (interface + 구현체)
├── sms/            # SMS 클라이언트
├── email/          # Email 클라이언트
├── push/           # Push 클라이언트
├── kakao/          # 카카오 알림톡 클라이언트
├── slack/          # Slack 클라이언트
├── marketing/      # 마케팅 허브 클라이언트
├── dto/            # Request/Response DTO
└── exception/      # 예외 처리

src/test/kotlin/com/gonza/payment/
├── service/        # 단위 테스트
├── repository/     # 통합 테스트 (H2)
├── controller/     # API 테스트 (MockMvc)
└── concurrency/    # 동시성 테스트 (Testcontainers)

load-test/
└── scenarios/      # k6 시나리오 (load / idempotency / balance-race / consume-race)

docs/               # 아키텍처, 시나리오 분석, 로드맵 문서
```

## Roadmap

| Phase | 상태 | 내용 |
|-------|------|------|
| **1. 모놀리스 정합성 완성** | 완료 | 3-Phase 결제, 멱등성, 조건부 UPDATE, k6 시나리오 4종 |
| **2. 의존 폭발 재현** | 진행 | 알림 채널 6개 동기 fan-out → 응답 시간 누적 측정 |
| **3. 이벤트 기반 분리** | 예정 | Spring `ApplicationEvent` → 메시지 큐 → 채널별 비동기 발송 |
| **4. MSA 분리 검토** | 예정 | 충전 / 구매 / 알림 도메인 경계 분석 |

자세한 단계별 의도와 학습 흐름은 [`docs/study-roadmap.md`](docs/study-roadmap.md) 참고.

## 추가 문서

- [`docs/architecture.md`](docs/architecture.md) — 전체 아키텍처 다이어그램 (Controller → Service → Repository, 3-Phase 흐름)
- [`docs/study-roadmap.md`](docs/study-roadmap.md) — Phase 1~4 단계별 의도
- [`docs/event-driven-architecture-analysis.md`](docs/event-driven-architecture-analysis.md) — EDA 도입 분석
- [`docs/msa-migration-analysis.md`](docs/msa-migration-analysis.md) — MSA 분리 검토
- [`docs/scenario/fanout-explosion/README.md`](docs/scenario/fanout-explosion/README.md) — 팬아웃 폭발 시나리오 분석
- [`docs/scenario/idempotency-charge/README.md`](docs/scenario/idempotency-charge/README.md) — 충전 멱등성 시나리오 분석