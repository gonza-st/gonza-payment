## 0) 목표/비목표

### 목표 (MVP)

* 유저가 **포인트를 충전**한다.

  * 충전은 **PG Mock**(외부 결제 대체)로 결제 승인 후 성공해야 한다.
  * **중복 요청(재시도)**에도 **중복 충전이 발생하지 않게(멱등성)** 한다.
* 유저가 **기프티콘을 구매**한다.

  * 포인트가 차감되고, 기프티콘이 생성된다.
  * **동시 구매/차감**에서 잔액이 음수가 되지 않는다.
* 유저가 **기프티콘을 사용(consume)** 한다.

  * 이미 사용한 기프티콘은 재사용 불가.
* Docker Compose로 `api + db`를 띄울 수 있다.
* 테스트는 전부 작성한다.

  * 단위 테스트 + 통합 테스트(Repository/DB 포함) + API 통합 테스트.

### 비목표

* 실제 PG 연동, 카드사/영수증, 정산, 관리자 화면
* 복잡한 상품/쿠폰 정책

---

## 1) 기술 스택/런타임

* Language: **Kotlin**
* Framework: **Spring Boot 3**
* Persistence: **Spring Data JPA + PostgreSQL**
* DB for local run: **PostgreSQL (docker-compose)**
* DB for tests: **H2** (빠르게) 또는 **Testcontainers(Postgres)** (DB behavior 동일성 최우선이면).

  * MVP는 H2로 가고, “락/동시성” 통합 테스트는 Testcontainers 권장.

---

## 2) 도메인 모델 (Aggregate/Entity)

### 2.1 User

* `id: UUID`
* `name: String`

### 2.2 Wallet (User의 잔액)

* `userId: UUID` (PK)
* `balance: Long` (포인트, >= 0)
* `version: Long` (Optimistic Locking용)

> Wallet을 “단일 row per user”로 유지하면 동시성/정합성 논의가 단순해지고 테스트도 깔끔해진다.

### 2.3 Charge (포인트 충전 트랜잭션)

* `id: UUID`
* `userId: UUID`
* `idempotencyKey: String` (유저별 unique)
* `amount: Long` (양수)
* `status: ChargeStatus` = `REQUESTED | PG_APPROVED | COMPLETED | FAILED`
* `pgTransactionId: String?`
* `createdAt, updatedAt`

**Unique Constraint**

* `(user_id, idempotency_key)` unique

### 2.4 GifticonProduct (기프티콘 상품)

* `id: UUID`
* `name: String`
* `price: Long`
* `isActive: Boolean`

### 2.5 Gifticon (구매된 기프티콘 인스턴스)

* `id: UUID`
* `userId: UUID`
* `productId: UUID`
* `code: String` (유니크 코드)
* `status: GifticonStatus` = `ISSUED | CONSUMED | CANCELED`
* `issuedAt, consumedAt?`

**Unique Constraint**

* `code` unique

### 2.6 PointLedger (선택 but 추천)

* `id: UUID`
* `userId: UUID`
* `type: LedgerType` = `CHARGE | PURCHASE | REFUND`
* `refId: UUID` (Charge.id 또는 Gifticon.id)
* `amountDelta: Long` (+충전, -구매)
* `createdAt`

> MVP에서 ledger는 “추적/감사”에 매우 유용. 테스트도 쉬워짐(“무엇이 잔액을 바꿨나”).

---

## 3) 유스케이스/서비스 (Application Service)

### 3.1 포인트 충전: `chargePoints(userId, amount, idempotencyKey)`

**흐름**

1. `Charge`를 `(userId, idempotencyKey)`로 조회

   * 존재하고 `COMPLETED`면 **이미 처리된 요청**이므로 같은 결과 반환(멱등)
   * 존재하지만 진행 중/실패 상태면 정책 결정:

     * MVP 권장: `FAILED`면 재시도 허용(새 idempotencyKey 필요) / 같은 키로는 재시도 시도 시 `409` 또는 상태 반환
2. 없으면 `Charge(status=REQUESTED)` 생성
3. PG Mock에 `approve(amount, idempotencyKey)` 호출

   * 성공: `pgTransactionId` 받음 → `PG_APPROVED`
   * 실패: `FAILED`
4. 성공이면 **Wallet balance 증가** + `PointLedger(+amount, ref=charge)` 기록
5. `Charge`를 `COMPLETED`로 마킹

**정합성 포인트**

* “Charge row + Wallet update”는 **하나의 트랜잭션**에서 처리
* 중복 충전 방지: unique constraint + 멱등 로직
* Wallet 증가는:

  * 방법 A(권장): `update wallet set balance = balance + :amount where user_id = :userId` (atomic update)
  * 방법 B: optimistic lock로 entity save

### 3.2 기프티콘 구매: `purchaseGifticon(userId, productId)`

**흐름**

1. 상품 조회 (active)
2. `Wallet.balance >= price` 검증 및 차감

   * **원자적 차감**: `update wallet set balance = balance - :price where user_id=:userId and balance >= :price`
   * 업데이트 row count = 0이면 `INSUFFICIENT_BALANCE`
3. `Gifticon` 생성 (`code` 발급, status=ISSUED)
4. `PointLedger(-price, ref=gifticon)` 기록

**정합성 포인트**

* 차감과 기프티콘 발급은 **동일 트랜잭션** 내

### 3.3 기프티콘 사용(consume): `consumeGifticon(userId, gifticonId)`

**흐름**

1. gifticon 조회 (userId 소유 확인)
2. status가 `ISSUED`인지 확인
3. `status=CONSUMED`, `consumedAt=now`로 변경

**경쟁 조건**

* 동시에 consume 2번 들어오면 1번만 성공해야 함

  * optimistic lock(버전) 또는 `update gifticon set status='CONSUMED' ... where id=:id and status='ISSUED'`

---

## 4) PG Mock 설계

### 4.1 인터페이스

* `PgClient.approve(idempotencyKey: String, amount: Long): PgApproveResult`

### 4.2 구현

* MVP에서는 “실제 외부 호출”이 아니라 **내부 Mock**이되, 통합 테스트에서 대체 가능하도록 **interface + bean**으로 분리.
* 결과:

  * 성공 시 `pgTransactionId` 반환
  * 실패 시 에러 코드(예: `DECLINED`, `TIMEOUT`)

**옵션**

* 환경변수로 실패율/지연을 넣어 시뮬레이션 가능

  * `PG_MOCK_FAIL_RATE=0.1`
  * `PG_MOCK_DELAY_MS=200`

---

## 5) API 설계 (REST)

Base: `/api`

### 5.1 유저 생성 (테스트 편의)

* `POST /users`

  * req: `{ "name": "..." }`
  * res: `{ "userId": "..."}`
* `GET /users/{userId}/wallet`

  * res: `{ "balance": 1000 }`

### 5.2 포인트 충전

* `POST /wallets/{userId}/charges`

  * headers: `Idempotency-Key: <string>`
  * req: `{ "amount": 5000 }`
  * res: `{ "chargeId": "...", "status": "COMPLETED", "balance": 5000 }`

멱등 규칙:

* 같은 `(userId, Idempotency-Key)`로 재호출 시 **동일 chargeId/결과**를 반환
* amount가 다르면 `409 Conflict` (같은 키에 다른 내용 금지)

### 5.3 상품

* `POST /products` (seed 용)

  * req: `{ "name": "스타벅스 아메리카노", "price": 4500 }`
* `GET /products`

### 5.4 기프티콘 구매

* `POST /users/{userId}/gifticons`

  * req: `{ "productId": "..." }`
  * res: `{ "gifticonId": "...", "code": "...", "status": "ISSUED", "balance": 500 }`

### 5.5 기프티콘 사용

* `POST /users/{userId}/gifticons/{gifticonId}/consume`

  * res: `{ "gifticonId": "...", "status": "CONSUMED" }`

---

## 6) DB 스키마 (DDL 요약)

* `users(id uuid pk, name text, created_at)`
* `wallets(user_id uuid pk fk users, balance bigint not null, version bigint not null)`
* `charges(id uuid pk, user_id uuid fk, idempotency_key text not null, amount bigint not null, status text not null, pg_transaction_id text null, created_at, updated_at)`

  * unique(user_id, idempotency_key)
* `gifticon_products(id uuid pk, name text, price bigint, is_active bool)`
* `gifticons(id uuid pk, user_id uuid fk, product_id uuid fk, code text unique, status text, issued_at, consumed_at null)`
* `point_ledgers(id uuid pk, user_id uuid fk, type text, ref_id uuid, amount_delta bigint, created_at)`

---

## 7) 트랜잭션/락 전략 (명시)

* 충전: `Charge + Wallet 증가 + Ledger + Charge 완료`까지 **단일 트랜잭션**
* 구매: `Wallet 차감 + Gifticon 발급 + Ledger` **단일 트랜잭션**
* consume: 상태 조건부 업데이트로 멱등/동시성 보호

Wallet 차감/증가 방식은 “원자적 update 쿼리”를 추천:

* 증가: `balance = balance + :amount`
* 차감: `balance = balance - :price where balance >= :price`

이 방식이면 DB가 동시성의 최종 책임을 진다.

---

## 8) 테스트 전략 (필수 목록)

### 8.1 단위 테스트 (Service 레벨)

* `chargePoints`:

  * 정상 충전 시 balance 증가 + ledger 기록 + charge COMPLETED
  * 같은 idempotencyKey 재호출 시 결과 동일(중복 충전 X)
  * 같은 idempotencyKey, 다른 amount 요청 시 409
  * PG 실패 시 charge FAILED, balance 변화 없음
* `purchaseGifticon`:

  * 잔액 충분하면 차감 + gifticon ISSUED + ledger 기록
  * 잔액 부족하면 실패(잔액 유지, gifticon 생성 X)
* `consumeGifticon`:

  * ISSUED → CONSUMED 전이 성공
  * 이미 CONSUMED면 실패 혹은 idempotent 정책(권장: 409)

### 8.2 통합 테스트 (DB 포함)

* Repository/JPA 매핑 테스트
* unique constraint 검증: `(userId, idempotencyKey)` 중복 insert 실패
* 원자적 차감 쿼리의 row count 기반 부족 처리 검증

### 8.3 동시성 테스트 (중요)

* “동시에 구매 2번”에서 잔액이 1번만 차감되도록 보장

  * 예: balance=5000, price=4500, 동시 2요청 → 1 성공, 1 실패
* “동시에 consume 2번” → 1 성공, 1 실패

> 동시성 테스트는 H2에서 DB 레벨 behavior가 다를 수 있으니, 가능하면 **Testcontainers(Postgres)**로 작성 권장.

### 8.4 API 통합 테스트 (MockMvc)

* 충전 API 멱등 동작
* 구매/사용 API 정상 플로우

---

## 9) Docker Compose 요구사항

구성:

* `db`: postgres
* `api`: spring boot jar (Dockerfile 빌드)

### docker-compose.yml (예시)

```yaml
services:
  db:
    image: postgres:16
    environment:
      POSTGRES_DB: points
      POSTGRES_USER: points
      POSTGRES_PASSWORD: points
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U points -d points"]
      interval: 5s
      timeout: 3s
      retries: 20

  api:
    build: .
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://db:5432/points
      SPRING_DATASOURCE_USERNAME: points
      SPRING_DATASOURCE_PASSWORD: points
      PG_MOCK_FAIL_RATE: "0.0"
      PG_MOCK_DELAY_MS: "0"
    ports:
      - "8080:8080"
    depends_on:
      db:
        condition: service_healthy
```

### Dockerfile (예시 방향)

* multi-stage로 gradle build 후 jar 실행
* `./gradlew test`는 CI에서 수행, docker build에서는 `bootJar`만 수행할지 정책 선택

---

## 10) 실행/검증 시나리오 (E2E)

1. docker compose up
2. 사용자 생성
3. 상품 등록
4. 충전(idempotency key 포함)
5. 기프티콘 구매
6. 기프티콘 사용
7. 같은 idempotency key로 충전 재호출 → balance 증가 없이 동일 응답

---

## 11) Claude Code에게 요청할 작업 범위 (명령문)

아래 그대로 붙여도 됨:

* Kotlin + Spring Boot 3로 위 스펙 구현
* JPA 엔티티/리포지토리/서비스/컨트롤러 구현
* Wallet 원자적 증감 쿼리 적용
* Charge 멱등성: unique(userId, idempotencyKey) + 동일키 재호출 시 동일 결과 반환 + 동일키에 다른 amount면 409
* Gifticon consume는 상태 조건부 업데이트로 동시성 보호
* 테스트 전부 작성:

  * unit tests (service)
  * integration tests (repository/db)
  * concurrency tests (prefer Testcontainers Postgres)
  * API tests (MockMvc)
* docker-compose + Dockerfile 포함
* `application-docker.yml`, `application-test.yml` 등 프로파일 분리

