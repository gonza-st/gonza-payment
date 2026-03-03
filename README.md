# gonza-payment

포인트 충전 / 기프티콘 구매 / 기프티콘 사용 시스템 MVP

## 기술 스택

- **Language**: Kotlin
- **Framework**: Spring Boot 3.3
- **Persistence**: Spring Data JPA + PostgreSQL
- **Test**: JUnit 5 + Mockito + H2 + Testcontainers
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
| 부하 테스트 | `docker compose --profile load up` | PostgreSQL | 충전 → 구매 → 사용 전체 시나리오 |
| 멱등성 테스트 | `docker compose --profile idempotency up` | PostgreSQL | 중복결제 방어 검증 |

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

## 결제 정합성 테스트

동일 `Idempotency-Key`로 동시에 충전 요청을 보내 중복결제가 발생하는지 검증한다.
PG 응답 지연 중 클라이언트가 재요청하는 상황을 시뮬레이션한다.

### 실행

```bash
# 기본 (20 VU, 10회 반복, 2건 동시)
PG_MOCK_DELAY_MS=2000 docker compose --profile idempotency up

# 파라미터 조절
TARGET_VUS=50 ITERATIONS=20 PG_MOCK_DELAY_MS=2000 docker compose --profile idempotency up

# setup.sh 사용 (테스트 후 브라우저 자동 오픈)
bash load-test/scenarios/idempotency/setup.sh              # 기본값
bash load-test/scenarios/idempotency/setup.sh 50 20 3      # 50 VU, 20회, 3건 동시
```

> 최초 실행 또는 코드 변경 시에는 `--build` 플래그를 추가한다.

### 테스트 흐름

```
1. GET  /users/{userId}/wallet            충전 전 잔액 조회
2. POST /wallets/{userId}/charges  ×2     동일 Idempotency-Key로 2건 동시 요청 (http.batch)
   ├─ 요청 A ─→ 서버 처리 중 (PG 지연 2초)
   └─ 요청 B ─→ 동시에 도달 (같은 키)
3. sleep 1s                               서버 처리 대기
4. GET  /users/{userId}/wallet            충전 후 잔액 조회
5. 잔액 차이 검증                          기대값: +10,000원 정확히 1건
```

### 파라미터

| 파라미터 | 기본값 | 환경변수 | 설명 |
|---------|--------|---------|------|
| VU 수 | 20 | `TARGET_VUS` | 동시 테스트 사용자 수 |
| VU당 반복 | 10 | `ITERATIONS` | 사용자별 충전 시도 횟수 |
| 동시 요청 | 2 | `CONCURRENT_REQUESTS` | 같은 키로 보내는 동시 요청 수 |
| PG 지연 | 0ms | `PG_MOCK_DELAY_MS` | PG 모의 응답 지연 (**2000 권장**) |

기본값 기준 총 테스트: **20명 x 10회 = 200건** (각 2건씩 동시 요청 → 총 HTTP 400건+)

### 응답 패턴 분류

동시 2건 요청에 대한 서버 응답 조합을 분류한다.

| 패턴 | 의미 | 판정 |
|------|------|------|
| `200 + 409` | 1건 성공 + 충돌 반환 | 이상적 (앱 레벨 방어) |
| `200 + 200` | 2건 모두 200 | 확인 필요 (멱등 반환 or 중복) |
| `200 + 5xx` | 1건 성공 + 미처리 예외 | 버그 (DataIntegrityViolation) |
| 전부 실패 | 200 응답 없음 | 장애 |

### 성공 기준

| 지표 | 기준 | 설명 |
|------|------|------|
| `duplicate_charges` | count == 0 | 잔액 기준 중복 충전 0건 |
| `balance_correct_rate` | rate > 0.95 | 충전 전후 잔액이 정확한 비율 95% 이상 |

### 실행 순서

Docker Compose가 아래 순서를 자동 관리한다.

```
db → api → seeder → idem-snapshot → k6-idem
                         │
                         └─ 테스트 대상 사용자의 초기 잔액을 JSON으로 저장
                            (HTML 리포트의 사용자별 검증 테이블에서 사용)
```

### 결과 확인

- 터미널: k6 summary + 사용자별 잔액 검증 테이블
- 브라우저: http://localhost:19000/idempotency-report.html

## 부하 테스트

k6 + Docker Compose 기반 부하 테스트. 로컬 설치 없이 Docker만 있으면 실행 가능.

### 실행

```bash
# 기본 (100 VU)
docker compose --profile load up

# 동시 사용자 수 변경
TARGET_VUS=1000 docker compose --profile load up

# setup.sh 사용 (테스트 후 브라우저 자동 오픈)
bash load-test/scenarios/load/setup.sh          # 기본 100 VU
bash load-test/scenarios/load/setup.sh 500      # 500 VU
```

> 최초 실행 또는 코드 변경 시에는 `--build` 플래그를 추가한다.

### 결과 확인

테스트가 끝나면 결과 리포트 서버가 자동으로 함께 뜹니다.

- 리포트: http://localhost:19000/report.html
- 파일: `load-test/results/report.html`, `load-test/results/summary.json`

### 구성

| 서비스 | 역할 |
|--------|------|
| `seeder` | API 호출로 상품 3개 + 사용자 30,000명 자동 생성 |
| `k6` | 부하 테스트 실행 (충전 → 구매 → 사용 시나리오) |
| `report` | nginx로 결과 HTML 서빙 (포트 19000) |

### 부하 프로필

| 단계 | 시간 | 동시 사용자 (VU) |
|------|------|-----------------|
| Ramp-up | 30s | 0 → 절반 |
| Sustained | 2m | 절반 유지 |
| Spike | 30s | 절반 → 최대 |
| Sustained High | 1m | 최대 유지 |
| Ramp-down | 30s | 최대 → 0 |

### 성공 기준

- HTTP 실패율 < 10%
- p95 응답시간 < 2초
- p99 응답시간 < 5초

## 정합성 전략

- **포인트 충전 멱등성**: `(userId, idempotencyKey)` unique constraint + 동일 키 재호출 시 동일 결과 반환
- **잔액 원자적 처리**: `UPDATE wallet SET balance = balance - :price WHERE balance >= :price` (DB 레벨 동시성 보호)
- **기프티콘 사용 보호**: `UPDATE gifticon SET status = 'CONSUMED' WHERE id = :id AND status = 'ISSUED'` (조건부 업데이트)
- **트랜잭션 단위**: 충전(Charge + Wallet + Ledger), 구매(Wallet + Gifticon + Ledger) 각각 단일 트랜잭션

## 프로젝트 구조

```
src/main/kotlin/com/gonza/payment/
├── domain/         # JPA 엔티티, 열거형
├── repository/     # Spring Data JPA 리포지토리
├── service/        # 비즈니스 로직
├── controller/     # REST API
├── pg/             # PG Mock (interface + 구현체)
├── dto/            # Request/Response DTO
└── exception/      # 예외 처리

src/test/kotlin/com/gonza/payment/
├── service/        # 단위 테스트
├── repository/     # 통합 테스트 (H2)
├── controller/     # API 테스트 (MockMvc)
└── concurrency/    # 동시성 테스트 (Testcontainers)
```
