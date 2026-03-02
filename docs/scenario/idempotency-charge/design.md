# 충전 서비스 분석 및 멱등성 개선 설계

## 1. 현재 충전 흐름 분석

충전은 크게 3단계로 구성된다. 현재는 이 3단계가 하나의 `@Transactional` 안에 묶여 있다.

### 1단계: 충전 요청 (멱등성 검사 + 레코드 생성)

```kotlin
// ChargeService.kt:30-44
val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)

// 사실상 동시요청 중에는 이전 요청에 대한 트랜잭션이 끝나기 전이므로
// 같은 idempotencyKey로 조회가 될 리 없음
if (existing != null) {
    return handleExistingCharge(existing, amount)
}

val charge = Charge(userId, idempotencyKey, amount)  // 상태: REQUESTED
chargeRepository.save(charge)
```

- 동일 `Idempotency-Key`로 이전 충전 이력이 있는지 SELECT
- 있으면 `handleExistingCharge`로 분기:
  - 금액이 다름 → `ConflictException` (같은 키로 다른 금액 차단)
  - `COMPLETED` → 200 + 기존 결과 반환 (멱등성)
  - `FAILED` → 에러 (새 키로 재시도 안내)
  - 그 외 (`REQUESTED`, `PG_APPROVED`) → 에러 (처리 진행 중)
- 없으면 새 charge 레코드 생성 (`REQUESTED` 상태)

### 2단계: PG 결제 요청

```kotlin
// ChargeService.kt:46-57
val pgResult = pgClient.approve(idempotencyKey, amount)

if (!pgResult.success) {
    charge.status = ChargeStatus.FAILED
    throw PgPaymentFailedException(pgResult.errorCode ?: "UNKNOWN")
}

charge.pgTransactionId = pgResult.pgTransactionId
charge.status = ChargeStatus.PG_APPROVED
```

- PG에 결제 승인 요청 (동기 blocking 호출)
- 실패 시 → charge 상태를 `FAILED`로 변경 + 예외 throw
- 성공 시 → PG 트랜잭션 ID 기록 + 상태를 `PG_APPROVED`로 변경

### 3단계: 잔액 충전

```kotlin
// ChargeService.kt:59-87
val updatedRows = walletRepository.addBalance(userId, amount)

pointLedgerRepository.save(
    PointLedger(userId, LedgerType.CHARGE, charge.id, amount)
)

charge.status = ChargeStatus.COMPLETED
chargeRepository.save(charge)

val wallet = walletRepository.findById(userId)
return ChargeResponse(chargeId = charge.id, status = charge.status, balance = wallet.balance)
```

- PG 승인된 금액만큼 사용자 지갑에 잔액 증가
- 포인트 원장(ledger)에 이력 기록 — "왜 이 잔액이 됐는지"의 추적용
- charge 상태를 `COMPLETED`로 최종 업데이트
- 현재 잔액을 조회하여 응답 반환

---

## 2. 현재 구조의 문제점

### 단일 트랜잭션 구조

```
@Transactional ─────────────────────────────────────────────────────┐
│ 1단계: SELECT → INSERT  │ 2단계: PG 호출 (수초) │ 3단계: 잔액 + 원장 │
└───────────────────────────────────────────────────────────────────┘
                                ↑
                     DB 커넥션을 점유한 채로 외부 호출 대기
```

3단계 전체가 하나의 `@Transactional`로 감싸져 있어 다음 문제가 발생한다.

### 문제 1: 동시 요청 시 500 에러 (92.5% 발생)

PostgreSQL READ COMMITTED에서 커밋되지 않은 INSERT는 다른 트랜잭션에서 보이지 않는다.

```
Request A: SELECT → null → INSERT (미커밋) → PG 호출 중...
Request B: SELECT → null (A의 INSERT가 안 보임) → INSERT → UNIQUE 위반 → 500
```

- `handleExistingCharge` 로직은 **순차 재시도에서만 동작**하는 방어
- 동시 요청에서는 SELECT에서 null이 돌아오므로 해당 로직에 도달하지 못함
- `DataIntegrityViolationException`이 처리되지 않아 500 에러 발생
- 사용자 입장: "결제된 건가? 실패한 건가?" 알 수 없음

### 문제 2: 유령 승인 (가장 위험)

PG는 승인했는데 트랜잭션이 롤백되면 DB에 기록이 없는 상태가 된다.

```
Request A: INSERT → PG 승인 완료 → 트랜잭션 타임아웃 → ROLLBACK (기록 삭제)
Request B: SELECT → null (A가 롤백됨) → INSERT → PG 재승인 → COMMIT
결과: PG 2건 승인 / DB 1건 기록 → 중복결제
```

### 문제 3: DB 커넥션 풀 고갈

PG 호출이 지연되는 동안 DB 커넥션을 점유한다. 동시 요청이 많아지면 커넥션 풀이 고갈되어 전체 서비스가 마비될 수 있다.

---

## 3. 부하 테스트 결과 (현재 버전)

`PG_MOCK_DELAY_MS=2000`, 20 VU × 10회 반복 = 200건 테스트 결과:

| 지표 | 결과 |
|------|------|
| 중복결제 | 0건 (DB UNIQUE 제약조건이 방어) |
| 잔액 정합성 | 100% |
| 응답 패턴 | `200+500` 92.5% / `200+200` 7.5% |
| 5xx 에러 | 185건 (DataIntegrityViolationException) |

- 중복결제 자체는 발생하지 않음 — DB 제약조건 덕분
- 하지만 **92.5%가 500 에러** — 사용자에게 의미 있는 응답을 주지 못함
- 개선 목표: 500 에러 → 409(처리 중) 또는 200(완료) 으로 전환

---

## 4. 개선 설계

### 핵심 원칙

> 외부 I/O(PG 호출)를 트랜잭션 밖으로 분리하고, PG 통신 결과를 별도 테이블에 기록한다.

### 트랜잭션 분리

현재 단일 트랜잭션을 3단계로 분리한다.

```
AS-IS (현재):
@Transactional ─────────────────────────────────────────────────────┐
│ SELECT → INSERT → [── PG 호출 (수초) ──] → UPDATE → 잔액 → 원장   │
└───────────────────────────────────────────────────────────────────┘

TO-BE (개선):
[TX1]                   [TX 없음]              [TX2]                [TX3]
SELECT → INSERT    →    PG 호출           →    PG 결과 기록    →    잔액 + 원장
(REQUESTED) COMMIT      (커넥션 미점유)         (pg_transaction)     (COMPLETED)
```

- **TX1**: charge 레코드 선점 → 빠르게 COMMIT하여 DB 커넥션 즉시 반환
- **PG 호출**: 트랜잭션 밖에서 실행 → DB 커넥션 미점유
- **TX2**: PG 응답 결과를 `pg_transaction` 테이블에 기록
- **TX3**: 잔액 증가 + 원장 기록 + charge 상태 COMPLETED

### PG 통신 기록 테이블 (pg_transaction)

charge 테이블은 충전 요청/상태를 관리하고, PG와의 통신 결과는 별도 테이블에 기록한다.

```
charge:          충전 요청/상태 관리     (REQUESTED → COMPLETED / FAILED)
pg_transaction:  PG 통신 기록           (요청/응답 독립 저장)
```

```sql
CREATE TABLE pg_transaction (
    id              UUID PRIMARY KEY,
    charge_id       UUID NOT NULL REFERENCES charge(id),
    idempotency_key VARCHAR NOT NULL,
    amount          BIGINT NOT NULL,
    pg_status       VARCHAR NOT NULL,    -- APPROVED / REJECTED / TIMEOUT / ERROR
    pg_transaction_id VARCHAR,           -- PG사가 발급한 거래 ID
    error_code      VARCHAR,
    requested_at    TIMESTAMP NOT NULL,  -- PG 요청 시각
    responded_at    TIMESTAMP,           -- PG 응답 시각
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);
```

이 테이블이 별도로 존재하면:

| 상황 | charge | pg_transaction | 복구 가능 여부 |
|------|--------|---------------|-------------|
| PG 호출 전 실패 | REQUESTED | 없음 | charge만 정리 |
| PG 승인 후 TX3 실패 | REQUESTED | APPROVED | PG 승인 기록이 남아있으므로 복구 가능 |
| 정상 완료 | COMPLETED | APPROVED | 정상 |

### 복구 전략

REQUESTED 상태로 오래 남아있는 charge를 스케줄러가 감지한다.

```
복구 스케줄러:
1. charge 상태가 REQUESTED이고 일정 시간(예: 5분) 경과한 건 조회
2. pg_transaction 테이블에서 해당 charge의 PG 결과 확인
   - APPROVED 기록 있음 → TX3 재시도 (잔액 + 원장 + COMPLETED)
   - 기록 없음 → PG 조회 API로 확인 후 처리
   - REJECTED/TIMEOUT → charge를 FAILED로 마킹
```

PG 결과가 charge와 분리되어 있기 때문에, 어떤 단계에서 실패하더라도 **PG에 무슨 일이 있었는지** 추적할 수 있다.

### 개선 후 동시 요청 흐름

```
Request A: TX1 (SELECT → INSERT → COMMIT) → PG 호출 중...
Request B: TX1 (SELECT → REQUESTED 발견!) → 409 Conflict 반환
```

TX1이 빠르게 커밋되므로 Request B의 SELECT에서 REQUESTED 상태를 감지할 수 있다.
더 이상 500 에러가 발생하지 않고, 사용자에게 의미 있는 응답을 반환한다.

| 상황 | 응답 | 사용자 경험 |
|------|------|-----------|
| 이미 완료됨 | 200 + 결과 반환 | "결제 완료됐구나" |
| 처리 진행 중 | 409 Conflict | "처리 중이구나" |
| PG 실패 | 에러 + 명확한 사유 | "실패했구나, 다시 해야지" |

---

## 5. 동시 INSERT 충돌 처리

### 문제

TX1을 빠르게 커밋하더라도, 두 요청이 거의 동시에 도달하면 SELECT 시점에 아직 커밋이 안 된 상태가 존재한다.

```
Request A: TX1 SELECT → null → INSERT → COMMIT (아직 안 됨)
Request B: TX1 SELECT → null (A 미커밋) → INSERT → UNIQUE 위반
```

이 경우 `DataIntegrityViolationException`이 발생하며, 이를 처리하지 않으면 기존과 동일한 500 에러가 된다.

### 선택: DataIntegrityViolationException catch

```kotlin
// TX1
try {
    chargeRepository.saveAndFlush(charge)
} catch (e: DataIntegrityViolationException) {
    // UNIQUE 위반 = 동시 요청으로 판단
    val conflict = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
    return handleExistingCharge(conflict!!, amount)  // → 409 or 200
}
```

DB UNIQUE 제약조건을 최종 방어선으로 활용하고, 예외 발생 시 기존 레코드를 조회하여 적절한 응답을 반환한다.

### 검토한 대안들

| 방식 | 동작 | 장점 | 단점 |
|------|------|------|------|
| **DB catch (채택)** | INSERT 시 UNIQUE 위반 catch → 기존 레코드 조회 | 인프라 추가 없음, DB가 정합성 보장 | 예외를 흐름 제어에 사용 |
| **Redis 분산 락** | TX 진입 전 `SETNX`로 선점 | DB 부하 감소, 깔끔한 분기 | Redis 장애 시 서비스 장애, Redis↔DB 정합성 갭 |
| **별도 lock 테이블** | `REQUIRES_NEW` TX로 lock 테이블에 먼저 INSERT | 비즈니스/락 로직 분리 | 결국 동일한 catch 필요, 테이블 추가 + 정리 필요 |

### 채택 근거

1. **DB UNIQUE가 진실의 원천** — Redis든 별도 테이블이든, 최종적으로 charge 테이블의 UNIQUE 제약조건이 중복을 판단한다. 앞에 어떤 레이어를 두더라도 catch는 여전히 필요하다.
2. **인프라 최소화** — Redis 의존성을 추가하면 Redis 장애가 곧 서비스 장애가 된다. 현재 시나리오에서 중복결제 방지가 핵심이며, 높은 트래픽 대응은 논외이다.
3. **스케일 전략** — 트래픽이 증가하여 DB 부하가 문제가 되면, COMPLETED 상태의 키를 Redis에 캐싱하는 **읽기 캐시** 전략으로 확장 가능하다. 이는 락이 아닌 캐시이므로 Redis 장애 시에도 DB fallback이 가능하다.
