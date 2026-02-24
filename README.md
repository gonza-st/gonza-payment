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
# 단위 + 통합 + API 테스트
./gradlew test

# 동시성 테스트 (Docker 필요)
./gradlew testWithDocker
```

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

## 부하 테스트

k6 + Docker Compose 기반 부하 테스트. 로컬 설치 없이 Docker만 있으면 실행 가능.

### 실행

```bash
# 기본 (100 VU)
docker compose up k6

# 동시 사용자 수 변경
TARGET_VUS=1000 docker compose up k6

# setup.sh 사용 (테스트 후 브라우저 자동 오픈)
bash load-test/setup.sh          # 기본 100 VU
bash load-test/setup.sh 500      # 500 VU
```

### 결과 확인

테스트가 끝나면 결과 리포트 서버가 자동으로 함께 뜹니다.

- 리포트: http://localhost:3000
- 파일: `load-test/results/report.html`, `load-test/results/summary.json`

### 구성

| 서비스 | 역할 |
|--------|------|
| `seeder` | API 호출로 상품 3개 + 사용자 30,000명 자동 생성 |
| `k6` | 부하 테스트 실행 (충전 → 구매 → 사용 시나리오) |
| `report` | nginx로 결과 HTML 서빙 (포트 3000) |

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
