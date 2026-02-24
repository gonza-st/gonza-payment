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
