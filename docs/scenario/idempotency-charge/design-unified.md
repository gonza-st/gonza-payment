# 충전 멱등성 개선 설계 (통합본)

> 작성 참여: currenjin, bh, wj

## 1. 시나리오 정의

### 1.1 문제 시나리오

클라이언트가 충전 API 요청 후 타임아웃이 발생하여, 동일 `Idempotency-Key`로 재시도 요청이 들어오는 상황.
PG는 승인을 완료했지만 응답이 늦어 클라이언트 타임아웃을 유발한다.

```
클라이언트          서버 (Spring)              PG (외부)
    │                    │                        │
    │─── ① 충전 요청 ───▶│                        │
    │                    │─── ② PG 승인 요청 ────▶│
    │                    │                        │ ③ PG 응답 지연
    │  ④ 타임아웃 발생    │       (대기중...)        │
    │                    │                        │
    │─── ⑤ 재요청 ──────▶│                        │
    │                    │                        │─── ⑥ 최초 요청 응답
    │                    │◀── PG 승인 완료 ────────│
    │                    │── 충전 처리 완료         │
    │                    │                        │
    │                    │─── ⑦ 재요청도 PG 호출 ─▶│
    │                    │◀── PG 승인 완료 ────────│
    │                    │── 충전 처리 완료         │
    │◀── 응답 ──────────│                        │
    │                    │                        │
    └──────── ⑨ 2건 중복결제 발생 ────────────────┘
```

### 1.2 전제 조건

| 항목 | 조건 |
|------|------|
| PG 통신 | 동기 (blocking) 호출 |
| PG 자체 멱등성 | 없음 (모킹 환경) |
| 클라이언트 → 서버 타임아웃 | 존재 (e.g. 1~5초) |
| 동일 Idempotency-Key 사용 | 재시도 시 동일 키 전송 |

### 1.3 토론 포인트

- 멱등 키(요청 단위) 설계
- Charge 상태 머신(REQUESTED/PG_APPROVED/COMPLETED/FAILED)
- "승인됐는데 우리 시스템은 실패로 본" 케이스 복구
- at-least-once 환경에서 중복 반영 방지

---

## 2. 현재 아키텍처 분석

### 2.1 현재 충전 흐름

```kotlin
// ChargeService.kt
@Transactional  // ← 전체가 하나의 트랜잭션
fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
    // 1. 멱등성 검사
    val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
    if (existing != null) return handleExistingCharge(existing, amount)

    // 2. Charge 생성 (REQUESTED)
    chargeRepository.save(charge)

    // 3. PG 승인 요청 (blocking) ← 여기서 수초간 대기
    val pgResult = pgClient.approve(idempotencyKey, amount)

    // 4. 잔액 증가 + 원장 기록 + COMPLETED
    ...
}
```

### 2.2 현재 방어 로직

| 방어 로직 | 구현 | 보호 범위 |
|-----------|------|----------|
| DB UNIQUE 제약 | `(user_id, idempotency_key)` | 동일 키로 2건 INSERT 차단 |
| SELECT 검사 | `findByUserIdAndIdempotencyKey` | 완료된 요청 재반환 |
| 상태 기반 분기 | `handleExistingCharge` | COMPLETED/FAILED/진행중 응답 구분 |
| Wallet 원자적 업데이트 | `UPDATE ... SET balance = balance + :amount` | 잔액 갱신 시 원자성 유지 |

### 2.3 문제점: 동시 재시도 타임라인

PostgreSQL READ COMMITTED 기준, PG delay 2초, 클라이언트 timeout 1초 가정:

```
t=0.0s  [Req 1] BEGIN TX
        [Req 1] SELECT charge → NULL
        [Req 1] INSERT charge(REQUESTED) — 아직 COMMIT 안 됨
        [Req 1] pgClient.approve() 시작 (2초 소요)

t=0.5s  [Req 2] BEGIN TX (클라이언트 재시도)
        [Req 2] SELECT charge → NULL  ← READ COMMITTED: 미커밋 INSERT 안 보임
        [Req 2] INSERT 시도 → BLOCKED  ← UNIQUE 인덱스의 row-level lock 대기

t=1.0s  [Req 3] BEGIN TX (클라이언트 재시도)
        [Req 3] SELECT → NULL → INSERT 시도 → BLOCKED

t=1.0s  [Client] 타임아웃. 3개 요청 모두 응답을 받지 못한 상태.

t=2.0s  [Req 1] PG 응답 수신 → 잔액 증가 → COMPLETED → COMMIT

t=2.0s  [Req 2] INSERT 재개 → unique_violation → DataIntegrityViolationException → 500 ❌
t=2.0s  [Req 3] INSERT 재개 → unique_violation → DataIntegrityViolationException → 500 ❌
```

### 2.4 식별된 문제

**P1. 긴 트랜잭션 → DB 커넥션 고갈**

PG 호출이 `@Transactional` 안에 있어, 응답까지 DB 커넥션을 점유한다.
동시 요청이 몰리면 커넥션 풀이 고갈되어 전체 서비스가 마비된다.

```
@Transactional 범위
┌───────────────────────────────────────────────────────┐
│ SELECT → INSERT → [ PG 호출 2초 대기 ] → UPDATE → COMMIT │  ← 커넥션 2초+ 점유
└───────────────────────────────────────────────────────┘

커넥션 풀 (max: 10)
┌────────────────────────────────────────┐
│ [PG 대기] [PG 대기] [PG 대기] ... ×10  │ ← 전부 점유
└────────────────────────────────────────┘
  새 요청 → 커넥션 획득 불가 → 타임아웃 → 503
```

**P2. 재시도 요청이 500 에러**

`DataIntegrityViolationException`을 catch하지 않아, 동시 INSERT 충돌 시 500으로 응답한다.
클라이언트는 서버 오류로 인식하고 추가 재시도를 시도할 수 있다.

**P3. 재시도 요청도 블로킹**

Req 1의 트랜잭션이 끝날 때까지 Req 2, 3의 INSERT가 row-level lock에 의해 대기한다.
재시도 요청이 빠르게 응답하지 못하고 불필요하게 2초간 블로킹된다.

**P4. 유령 승인 (가장 위험)**

PG 호출 중 트랜잭션이 타임아웃으로 롤백되면, PG에서는 승인됐지만 DB에는 기록이 없는 상태가 된다.
이후 재시도가 들어오면 기존 레코드가 없으므로 PG 승인이 한 번 더 일어난다.

```
[Req 1] INSERT(REQUESTED) → PG 승인 성공 → ★ TX 타임아웃 ROLLBACK → DB에 기록 없음
[Req 2] SELECT → NULL → INSERT → PG 승인 (2번째!) → COMMIT
결과: PG 2건 승인, DB 1건 기록 → 중복결제
```

### 2.5 근본 원인

> **외부 I/O 호출(PG)이 트랜잭션 안에 묶여 있다.**

이 하나의 원인이 P1~P4 모든 문제를 만들어낸다.

| 근본 원인 | 영향 |
|---|---|
| 외부 API 호출이 트랜잭션 안에 있음 | 긴 TX → 커넥션 고갈, 타임아웃, 유령 승인 |
| UniqueConstraint 예외 미처리 | 재시도 요청이 500 에러 |

---

## 3. 해결: 2-Phase 트랜잭션 분리

### 3.1 핵심 원칙

> **외부 I/O(PG 호출)를 트랜잭션 밖으로 분리하고, 상태 머신 기반으로 충전 프로세스를 단계별로 관리한다.**

1. 외부 API 호출을 트랜잭션 밖으로 분리하여 DB 커넥션 점유 최소화
2. UniqueConstraint를 동시성 제어의 1차 방어선으로 활용
3. PG 승인 결과를 먼저 DB에 기록한 후 후속 처리를 수행하여 정합성 보장

### 3.2 AS-IS vs TO-BE

```
AS-IS: 단일 트랜잭션
┌──────────────────────────────────────────────────────┐
│ SELECT → INSERT → [── PG 호출 2초 ──] → UPDATE → COMMIT │
└──────────────────────────────────────────────────────┘
                     ↑ DB 커넥션 점유 상태로 외부 호출

TO-BE: 트랜잭션 분리
┌────────────────────┐                    ┌──────────────────────────┐
│ TX-1: INSERT        │                    │ TX-2: 상태 변경 + 잔액    │
│ (REQUESTED) → COMMIT│ → [PG 호출] →     │ + 원장 → COMPLETED       │
│ (~10ms)             │   (커넥션 미점유)   │ (~10ms)                  │
└────────────────────┘                    └──────────────────────────┘
```

### 3.3 상태 머신

```
             ┌─────────────┐
             │  REQUESTED  │ ← TX-1: Charge 레코드 생성
             └──────┬──────┘
                    │
           PG 호출 (TX 밖)
                    │
            ┌───────▼───────┐
       ┌────│  PG_APPROVED  │
       │    └───────┬───────┘
       │            │
       │   잔액 증가 + 원장 기록 (TX-2)
       │            │
       │    ┌───────▼───────┐
       │    │   COMPLETED   │ ← 최종 완료
       │    └───────────────┘
       │
       │    ┌───────────────┐
       └───▶│    FAILED     │ ← PG 실패 시
            └───────────────┘
```

각 상태의 의미:
- **REQUESTED**: Charge 레코드가 생성됨. PG 호출 전 또는 PG 호출 진행 중.
- **PG_APPROVED**: PG 승인 완료. 잔액 반영 직전.
- **COMPLETED**: 잔액 증가 + 원장 기록 완료. 최종 상태.
- **FAILED**: PG 승인 실패. 새로운 멱등키로 재시도 필요.

### 3.4 상세 흐름

```
chargePoints(userId, amount, idempotencyKey)  — @Transactional 없음
│
├─ Phase 1: Charge 선점 [TX-1]
│   ├─ try: INSERT charge(REQUESTED) → COMMIT
│   └─ catch UniqueConstraint:
│       └─ SELECT 기존 charge
│           ├─ amount 불일치 → 잘못된 요청 (422)
│           ├─ COMPLETED    → 캐시된 결과 반환 (200)
│           ├─ FAILED       → 실패 응답 (409)
│           └─ REQUESTED / PG_APPROVED → 처리 중 응답 (409)
│
├─ Phase 2: PG 승인 [TX 없음]
│   ├─ pgClient.approve(idempotencyKey, amount)
│   └─ 실패 시: charge.status = FAILED → COMMIT → 예외
│
└─ Phase 3: 내부 반영 [TX-2]
    ├─ charge.status = PG_APPROVED, pgTransactionId 저장
    ├─ walletRepository.addBalance(userId, amount)
    ├─ pointLedgerRepository.save(...)
    ├─ charge.status = COMPLETED
    └─ COMMIT
```

### 3.5 의사 코드

```kotlin
// 오케스트레이터 — @Transactional 없음
fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
    // Phase 1: Charge 선점 (TX-1)
    val result = createOrGetCharge(userId, amount, idempotencyKey)

    return when (result) {
        is AlreadyCompleted -> result.toResponse()           // 200: 멱등성 반환
        is PreviouslyFailed -> throw ConflictException(...)  // 409: 새 키로 재시도 안내
        is InProgress       -> throw ConflictException(...)  // 409: 처리 중
        is Created          -> {
            // Phase 2: PG 호출 (TX 밖)
            val pgResult = pgClient.approve(idempotencyKey, amount)

            // Phase 3: 결과 반영 (TX-2)
            completeCharge(result.charge, pgResult, userId, amount)
        }
    }
}

// TX-1: Charge 레코드 선점
@Transactional
fun createOrGetCharge(userId: UUID, amount: Long, key: String): ChargeCreationResult {
    val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, key)
    if (existing != null) {
        return when {
            existing.amount != amount              -> throw UnprocessableException(...)
            existing.status == COMPLETED           -> AlreadyCompleted(existing)
            existing.status == FAILED              -> PreviouslyFailed(existing)
            else /* REQUESTED, PG_APPROVED */      -> InProgress(existing)
        }
    }

    return try {
        val charge = Charge(userId = userId, idempotencyKey = key, amount = amount)
        chargeRepository.saveAndFlush(charge)
        Created(charge)
    } catch (e: DataIntegrityViolationException) {
        // 동시 요청에 의한 UNIQUE 위반 → 기존 레코드 재조회
        val conflict = chargeRepository.findByUserIdAndIdempotencyKey(userId, key)
            ?: throw IllegalStateException("Charge not found after unique violation")
        resolveConflict(conflict, amount)
    }
}

// TX-2: PG 결과 반영 + 잔액 증가 + 원장 기록
@Transactional
fun completeCharge(charge: Charge, pgResult: PgApproveResult, ...): ChargeResponse {
    val current = chargeRepository.findById(charge.id).orElseThrow { ... }

    // 다른 요청이 이미 처리했는지 확인
    if (current.status != REQUESTED) {
        return handleExistingCharge(current, amount)
    }

    if (!pgResult.success) {
        current.status = FAILED
        chargeRepository.save(current)
        throw PgPaymentFailedException(...)
    }

    current.pgTransactionId = pgResult.pgTransactionId
    current.status = PG_APPROVED

    walletRepository.addBalance(userId, amount)
    pointLedgerRepository.save(PointLedger(...))

    current.status = COMPLETED
    chargeRepository.save(current)

    return ChargeResponse(...)
}
```

### 3.6 멱등성 응답 전략

원칙: **같은 요청을 여러 번 보내도 동일한 결과를 반환한다.** (Stripe, Toss Payments 등 업계 표준 패턴)

| Charge 상태 | 조건 | HTTP 응답 | 설명 |
|-------------|------|-----------|------|
| — | amount 불일치 | **422** Unprocessable Entity | 같은 멱등키에 다른 금액 → 잘못된 요청 |
| REQUESTED / PG_APPROVED | amount 일치 | **409** Conflict | 이전 요청이 아직 처리 중 |
| COMPLETED | amount 일치 | **200** + 캐시된 결과 | 원래 성공 응답을 그대로 반환 |
| FAILED | amount 일치 | **409** Conflict | 실패한 건. 새 멱등키로 재시도 안내 |

---

## 4. 레이스 컨디션 시나리오별 검증 (개선 후)

### 시나리오 A: 정상 재시도 (최초 요청 완료 후 재요청)

```
Req A: createOrGetCharge → Created → PG 호출 → completeCharge → COMPLETED
Req B: createOrGetCharge → AlreadyCompleted → 기존 결과 반환 (200) ✅
```

### 시나리오 B: 동시 요청 (최초 요청 진행 중 재요청)

```
t=0.00s  [Req 1] TX-1: INSERT charge(REQUESTED) → COMMIT (~10ms)
t=0.01s  [Req 1] PG 호출 시작 (TX 밖, 커넥션 미점유)

t=0.50s  [Req 2] TX-1: INSERT 시도
         → UniqueConstraint catch (Req 1이 이미 COMMIT, 블로킹 없음)
         → SELECT → REQUESTED, amount 일치
         → 409 "처리 중" 즉시 응답 (~10ms)

t=1.00s  [Req 3] TX-1: INSERT 시도
         → UniqueConstraint catch
         → SELECT → REQUESTED
         → 409 "처리 중" 즉시 응답

t=2.01s  [Req 1] PG 응답 수신
t=2.01s  [Req 1] TX-2: PG_APPROVED → 잔액 증가 → 원장 기록 → COMPLETED → COMMIT
t=2.02s  [Req 1] 200 응답 (클라이언트는 이미 타임아웃이라 못 받음)

t=3.00s  [Req 4] 클라이언트 backoff 후 재시도
         → INSERT → UniqueConstraint catch → SELECT → COMPLETED
         → 200 { chargeId, status=COMPLETED, balance } ← 멱등성 반환
```

**핵심 차이**: TX-1이 빠르게 커밋되므로, 재시도 요청의 SELECT에서 REQUESTED 상태의 레코드를 확인할 수 있다.

### 시나리오 C: 극단적 동시 요청 (INSERT 충돌)

```
T1   [Req A] SELECT → null            [Req B] SELECT → null
T2   [Req A] INSERT → 성공 (COMMIT)    [Req B] INSERT → unique_violation
T3                                     [Req B] catch → 재조회 → REQUESTED 발견
T4                                     [Req B] → InProgress 반환 (409)
T5   [Req A] PG 호출 → completeCharge
```

`DataIntegrityViolationException`을 명시적으로 catch하여 안전하게 처리한다.

### 시나리오 D: PG 호출 중 서버 장애 (유령 승인 방지)

```
T1   [Req A] INSERT (REQUESTED) → COMMITTED       DB에 REQUESTED 저장
T2   [Req A] PG 호출 시작                          PG 승인 처리 중
T3   [Req A] ★ 서버 크래시                          PG 승인 완료
─────────── 서버 재시작 후 ───────────
T4   [Req B] SELECT → REQUESTED 발견!
T5   [Req B] → InProgress 반환 (409)
     OR
     → 복구 로직에서 PG 상태 조회 후 처리
```

**트랜잭션 분리의 효과**: INSERT가 이미 커밋되었으므로, 서버가 크래시해도 REQUESTED 상태의 레코드가 DB에 남아있다.

---

## 5. 시퀀스 다이어그램 (개선 후 전체 흐름)

```
클라이언트          서버 (ChargeService)       DB                    PG
    │                    │                     │                     │
    │── ① 충전 요청 ────▶│                     │                     │
    │                    │── [TX1 BEGIN] ──────▶│                     │
    │                    │   SELECT charge      │                     │
    │                    │◀── null ─────────────│                     │
    │                    │   INSERT (REQUESTED)  │                     │
    │                    │──────────────────────▶│                     │
    │                    │── [TX1 COMMIT] ──────▶│                     │
    │                    │                     │                     │
    │                    │────── ② PG 승인 요청 (트랜잭션 밖) ────────▶│
    │                    │                     │                     │
    │ ③ 클라 타임아웃     │                     │        ④ PG 지연     │
    │                    │                     │                     │
    │── ⑤ 재요청 ───────▶│                     │                     │
    │                    │── [TX BEGIN] ───────▶│                     │
    │                    │   SELECT charge      │                     │
    │                    │◀── REQUESTED ────────│                     │
    │                    │── [TX COMMIT] ───────│                     │
    │◀── 409 Conflict ──│                     │                     │
    │   "처리 진행 중"    │                     │                     │
    │                    │                     │                     │
    │                    │◀──────────── ⑥ PG 승인 완료 ──────────────│
    │                    │── [TX2 BEGIN] ──────▶│                     │
    │                    │   UPDATE (COMPLETED)  │                     │
    │                    │   잔액 증가            │                     │
    │                    │   원장 기록            │                     │
    │                    │── [TX2 COMMIT] ──────▶│                     │
    │                    │                     │                     │
    │── ⑦ 재재요청 ─────▶│                     │                     │
    │                    │── SELECT charge ────▶│                     │
    │                    │◀── COMPLETED ────────│                     │
    │◀── 200 OK ────────│                     │                     │
    │   (기존 결과 반환)   │                     │                     │
```

**결과: PG 1건 승인 / DB 1건 기록 / 중복결제 없음**

---

## 6. "승인됐는데 실패로 본" 케이스 복구

PG는 승인했지만, TX-2(결과 반영) 단계에서 서버 장애가 발생하면 charge가 REQUESTED 상태로 남는다.
DB에 REQUESTED 레코드가 존재하므로 재시도 시 409로 응답되고, 중복 PG 승인은 방지된다.
하지만 잔액이 반영되지 않은 채 stuck 되는 문제가 있다.

### 복구 방안: REQUESTED 상태 만료 스케줄러

```kotlin
@Scheduled(fixedDelay = 60_000)  // 1분마다 실행
fun cleanupStaleCharges() {
    val threshold = Instant.now().minus(Duration.ofMinutes(5))
    val staleCharges = chargeRepository.findByStatusAndCreatedAtBefore(
        ChargeStatus.REQUESTED, threshold
    )

    staleCharges.forEach { charge ->
        // PG 상태 조회 API가 있다면 실제 승인 여부 확인 후 처리
        // - PG 승인됨 → completeCharge() 호출하여 잔액 반영
        // - PG 승인 안 됨 → FAILED 마킹
        charge.status = ChargeStatus.FAILED
        charge.updatedAt = Instant.now()
        chargeRepository.save(charge)
    }
}
```

실제 운영 환경에서는 PG 상태 조회 API를 통해 승인 여부를 확인한 후 분기하는 것이 이상적이다.

---

## 7. 추가 방어 계층

### 7.1 PG 호출 타임아웃 설정

현재 `MockPgClient`에는 호출 측 타임아웃이 없다. 실제 환경에서는 반드시 설정해야 한다.

```kotlin
@Component
class PgClientWithTimeout(
    private val delegate: PgClient,
    @Value("\${pg.timeout-ms:5000}") private val timeoutMs: Long
) : PgClient {

    override fun approve(idempotencyKey: String, amount: Long): PgApproveResult {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<PgApproveResult> {
            delegate.approve(idempotencyKey, amount)
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            PgApproveResult(success = false, errorCode = "PG_TIMEOUT")
        } finally {
            executor.shutdown()
        }
    }
}
```

### 7.2 클라이언트 재시도 가이드라인

| 항목 | 권장 값 |
|------|---------|
| 재시도 시 동일 Idempotency-Key 사용 | 필수 |
| 최대 재시도 횟수 | 3회 |
| 재시도 간격 | Exponential Backoff (1s → 2s → 4s) |
| 409 Conflict 수신 시 | 일정 시간 후 상태 조회 API로 확인 |

---

## 8. 개선 전후 비교

### 8.1 문제별 해결 여부

| 문제 | AS-IS | TO-BE | 해결 |
|------|-------|-------|------|
| P1. 커넥션 고갈 | PG 2초간 TX 열림 | TX-1 ~10ms, TX-2 ~10ms | O |
| P2. 재시도 500 | `DataIntegrityViolationException` 미처리 | catch 후 기존 charge 조회 → 409/200 | O |
| P3. 재시도 블로킹 | INSERT row-lock에서 2초 대기 | TX-1 이미 COMMIT → 즉시 조회 | O |
| P4. 유령 승인 | TX 롤백 시 charge 삭제 → 중복 PG 승인 | INSERT 이미 COMMIT → REQUESTED 잔존 → 중복 방지 | O |

### 8.2 보호 범위 비교

| 시나리오 | AS-IS | TO-BE |
|----------|-------|-------|
| 완료 후 재시도 | 멱등성 반환 | 멱등성 반환 |
| 진행 중 재시도 (SELECT 시점) | 트랜잭션 미커밋 시 감지 불가 | REQUESTED 상태 감지 가능 |
| 동시 INSERT 충돌 | 500 에러 (미처리 예외) | catch 후 안전 처리 |
| PG 지연 중 트랜잭션 롤백 | 유령 승인 → 중복결제 | REQUESTED 레코드 잔존 → 중복 방지 |
| DB 커넥션 풀 고갈 | PG 대기 중 커넥션 점유 | PG 호출 시 커넥션 미점유 |
| 서버 크래시 복구 | 기록 없음 (롤백됨) | REQUESTED 레코드로 복구 가능 |

### 8.3 트랜잭션 점유 시간 비교

```
AS-IS: ████████████████████████████████████ (PG 응답까지 전체 점유)
        SELECT  INSERT  [── PG 호출 ──]  UPDATE  COMMIT

TO-BE: ████                    ████████
       TX-1                    TX-2
       (SELECT+INSERT)        (UPDATE+잔액+원장)
              [── PG 호출 ──]
              (커넥션 미점유)
```

---

## 9. 구현 검증 (테스트)

### 9.1 같은 Idempotency-Key로 3번 호출해도 잔액 1번만 증가

```kotlin
@Test
fun `동일 멱등키 3회 요청 시 잔액은 1회만 증가한다`() {
    // given
    val userId = seedUser()
    val initialBalance = getBalance(userId)
    val idempotencyKey = "idem-${UUID.randomUUID()}"
    val amount = 10_000L

    // when: 동시에 3번 요청
    val futures = (1..3).map {
        executor.submit<Result> {
            chargePoints(userId, amount, idempotencyKey)
        }
    }
    val results = futures.map { it.get() }

    // then: 1건 성공, 나머지는 409
    val successes = results.count { it.status == 200 }
    val conflicts = results.count { it.status == 409 }
    assertThat(successes).isEqualTo(1)
    assertThat(conflicts).isEqualTo(2)

    // then: 잔액은 정확히 1번만 증가
    val finalBalance = getBalance(userId)
    assertThat(finalBalance).isEqualTo(initialBalance + amount)
}
```

### 9.2 승인 지연 상황에서 재호출 시 동일 결과 반환

```kotlin
@Test
fun `PG 승인 지연 중 재호출 시 409 후 완료 시 200 반환`() {
    // given: PG delay 2초 설정
    setPgDelay(2000)
    val userId = seedUser()
    val idempotencyKey = "idem-${UUID.randomUUID()}"
    val amount = 10_000L

    // when: 첫 요청 (비동기)
    val firstRequest = executor.submit { chargePoints(userId, amount, idempotencyKey) }

    // when: 0.5초 후 재요청
    Thread.sleep(500)
    val retryResult = chargePoints(userId, amount, idempotencyKey)

    // then: 재요청은 409 (처리 중)
    assertThat(retryResult.status).isEqualTo(409)

    // when: 첫 요청 완료 대기
    firstRequest.get()

    // when: 다시 재요청
    val finalResult = chargePoints(userId, amount, idempotencyKey)

    // then: 완료된 결과를 멱등하게 반환
    assertThat(finalResult.status).isEqualTo(200)
    assertThat(finalResult.body.status).isEqualTo("COMPLETED")
    assertThat(finalResult.body.balance).isEqualTo(getBalance(userId))
}
```

### 9.3 k6 부하 테스트 검증 항목

기존 `load-test/scenarios/idempotency/test.js`의 검증 기준:

| 메트릭 | 기대값 (수정 전) | 기대값 (수정 후) |
|--------|-----------------|-----------------|
| `duplicate_charges` | 0 (DB 제약 방어) | 0 (앱 레벨 방어) |
| `balance_correct_rate` | > 95% | 100% |
| 주요 응답 패턴 | `200 + 500` (미처리 예외) | `200 + 409` (이상적) |
| 5xx 에러 | 다수 발생 | 0건 |
| 전체 판정 | WARN | PASS |

---

## 10. 구현 체크리스트

- [ ] `ChargeStatus`에 `PG_APPROVED` 상태 추가
- [ ] `Charge` 엔티티에 `amount`, `pgTransactionId` 필드 추가
- [ ] `ChargeService.chargePoints()` 트랜잭션 분리 (오케스트레이터 패턴)
- [ ] `createOrGetCharge()` 구현 (TX-1 + DataIntegrityViolationException 핸들링)
- [ ] `completeCharge()` 구현 (TX-2 + 잔액/원장 반영)
- [ ] 멱등성 응답 전략 구현 (200/409/422)
- [ ] PG 호출 타임아웃 래퍼 구현
- [ ] REQUESTED 상태 만료 스케줄러 구현
- [ ] 동시성 테스트 작성 (Testcontainers 기반)
- [ ] k6 멱등성 시나리오 부하 테스트 실행 및 검증
