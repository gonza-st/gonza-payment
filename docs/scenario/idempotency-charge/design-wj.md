# 클라이언트 타임아웃 재시도에 의한 중복결제 방지 설계

## 1. 시나리오 정의

### 1.1 문제 시나리오

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
    │                    │─── ⑦ 재요청 수용 ──────▶│
    │                    │                        │─── ⑧ PG 재호출
    │                    │◀── PG 승인 완료 ────────│
    │                    │── 충전 처리 완료         │
    │◀── 응답 ──────────│                        │
    │                    │                        │
    └──────── ⑨ 2건 중복결제 발생 ────────────────┘
```

### 1.2 시나리오 전제 조건

| 항목 | 조건 |
|------|------|
| PG 통신 | 동기 (blocking) 호출 |
| PG 자체 멱등성 | 없음 (모킹 환경) |
| 서버 → PG 타임아웃 | 미설정 |
| 클라이언트 → 서버 타임아웃 | 존재 (e.g. 5초) |
| 동일 Idempotency-Key 사용 | 재시도 시 동일 키 전송 |

---

## 2. 현재 아키텍처 분석

### 2.1 현재 결제 흐름

```kotlin
// ChargeService.kt
@Transactional
fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
    // 1단계: 멱등성 검사
    val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
    if (existing != null) return handleExistingCharge(existing, amount)

    // 2단계: Charge 레코드 생성 (REQUESTED)
    val charge = Charge(userId, idempotencyKey, amount)
    chargeRepository.save(charge)

    // 3단계: PG 호출 (동기 blocking) ← 여기서 지연 발생
    val pgResult = pgClient.approve(idempotencyKey, amount)

    // 4~7단계: 후속 처리 (상태변경, 잔액증가, 원장기록)
    ...
}
```

### 2.2 현재 보유한 방어 수단

| 방어 수단 | 구현 방식 | 보호 범위 |
|-----------|----------|----------|
| DB UNIQUE 제약조건 | `(user_id, idempotency_key)` | 동일 키로 2건 INSERT 차단 |
| 멱등성 검사 (SELECT) | `findByUserIdAndIdempotencyKey` | 완료된 요청 재반환 |
| 상태 기반 분기 | COMPLETED/FAILED/진행중 분기 | 상태별 적절한 응답 |

### 2.3 현재 구조의 핵심 취약점

#### 취약점 1: 트랜잭션 내부의 외부 I/O 호출

```
@Transactional 범위
┌─────────────────────────────────────────────────┐
│ SELECT → INSERT → [PG 호출 (수초~수십초)] → UPDATE │
└─────────────────────────────────────────────────┘
                     ↑
              DB 커넥션을 점유한 채로 외부 호출 대기
```

전체 `chargePoints()` 메서드가 하나의 `@Transactional`로 감싸져 있다. PG 호출이 지연되면 DB 커넥션을 점유한 채로 대기하게 되어, 이 시간 동안 다른 요청이 해당 커넥션을 사용할 수 없다.

#### 취약점 2: Check-then-Act 레이스 컨디션

PostgreSQL의 기본 격리 수준은 **READ COMMITTED**이다. 이 환경에서:

```
시간  Request A (최초 요청)                    Request B (재시도)
─────────────────────────────────────────────────────────────────
T1   BEGIN
T2   SELECT charge → null (없음)
T3   INSERT charge (REQUESTED) [uncommitted]
T4   pgClient.approve() 시작 (지연...)
                                              BEGIN
T5                                            SELECT charge → null
                                              (A의 INSERT는 미커밋이므로 보이지 않음)
T6                                            INSERT charge → BLOCKED
                                              (UNIQUE 인덱스 row-level lock 대기)
T7   PG 응답 수신 → UPDATE → COMMIT
T8                                            INSERT 실패 (unique_violation)
                                              → DataIntegrityViolationException
                                              → 500 Internal Server Error
```

- Request A의 INSERT가 커밋되기 전이므로 Request B의 SELECT에서 기존 레코드가 보이지 않는다.
- Request B의 INSERT는 UNIQUE 인덱스의 row-level lock에 의해 **블로킹**된다.
- Request A가 커밋되면 Request B의 INSERT는 `unique_violation`으로 실패한다.
- **중복결제 자체는 DB 제약조건이 막아주지만**, `DataIntegrityViolationException`이 처리되지 않아 500 에러가 발생한다.

#### 취약점 3: 트랜잭션 롤백 시 PG 유령 승인 (가장 위험)

```
시간  Request A                                PG           DB
──────────────────────────────────────────────────────────────
T1   BEGIN
T2   INSERT charge (REQUESTED)
T3   pgClient.approve() ──────────────────▶ 승인 처리 시작
T4   ... (PG 지연 대기 중) ...
T5   ★ 트랜잭션 타임아웃 / DB 커넥션 풀 만료
     → 트랜잭션 자동 ROLLBACK                              charge 삭제됨
T6                                           승인 완료!
     (PG에서는 결제 승인됨, DB에는 기록 없음)
T7   ──────────────────────────────────────────────────────
     Request B (재시도)
T8   BEGIN
T9   SELECT charge → null (A는 롤백됨)
T10  INSERT charge (REQUESTED)
T11  pgClient.approve() → PG 승인 (2번째)
T12  UPDATE → COMMIT
──────────────────────────────────────────────────────────
     결과: PG에서 2건 승인, DB에는 1건만 기록 → 중복결제!
```

이 시나리오가 사용자가 기술한 "⑨ 2건 중복결제 발생"의 핵심 원인이다.

- Request A의 트랜잭션이 PG 응답 대기 중 타임아웃으로 롤백된다.
- PG는 이미 승인을 완료했지만 DB에는 기록이 없다 (**유령 승인**).
- Request B가 동일 키로 재시도하면 기존 레코드가 없으므로 신규 처리된다.
- PG에서 다시 승인이 나가고, 최종적으로 **PG 2건 승인 / DB 1건 기록**이 된다.

#### 취약점 4: DB 커넥션 풀 고갈

PG 지연이 길어질수록 트랜잭션도 길어지고, 커넥션이 반환되지 않는다. 동시 요청이 많으면 커넥션 풀이 고갈되어 전체 서비스가 마비된다.

```
커넥션 풀 (max: 10)
┌────────────────────────────────────────┐
│ [PG 대기] [PG 대기] [PG 대기] ... ×10  │ ← 전부 점유
└────────────────────────────────────────┘
  새 요청 → 커넥션 획득 불가 → 타임아웃 → 503
```

---

## 3. 개선 설계

### 3.1 핵심 원칙

> **외부 I/O(PG 호출)를 트랜잭션 밖으로 분리하고, 상태 머신 기반으로 충전 프로세스를 단계별로 관리한다.**

### 3.2 상태 머신 재설계

```
                    ┌───────────────┐
                    │   REQUESTED   │ ← Charge 레코드 생성 (트랜잭션 1)
                    └───────┬───────┘
                            │
                   PG 호출 (트랜잭션 밖)
                            │
                    ┌───────▼───────┐
              ┌─────│  PG_APPROVED  │ ← PG 승인 결과 기록 (트랜잭션 2)
              │     └───────┬───────┘
              │             │
              │    잔액 증가 + 원장 기록
              │             │
              │     ┌───────▼───────┐
              │     │   COMPLETED   │ ← 최종 완료 (트랜잭션 3)
              │     └───────────────┘
              │
              │     ┌───────────────┐
              └────▶│    FAILED     │ ← PG 실패 시
                    └───────────────┘
```

### 3.3 트랜잭션 분리 전략

**현재 (AS-IS): 단일 트랜잭션**
```
@Transactional ─────────────────────────────────────────────────┐
│ SELECT → INSERT → PG 호출 → UPDATE → 잔액증가 → 원장기록     │
└───────────────────────────────────────────────────────────────┘
```

**개선 (TO-BE): 3단계 트랜잭션**
```
[트랜잭션 1]                [트랜잭션 밖]          [트랜잭션 2]
┌──────────────────┐   ┌──────────────────┐   ┌───────────────────────┐
│ SELECT → INSERT  │ → │ PG 호출 (blocking)│ → │ 상태변경 + 잔액 + 원장 │
│ (REQUESTED)      │   │ (커넥션 미점유)    │   │ (COMPLETED)           │
└──────────────────┘   └──────────────────┘   └───────────────────────┘
```

### 3.4 의사 코드 (개선안)

```kotlin
// ChargeService.kt (개선안)

/**
 * 트랜잭션 1: Charge 레코드 선점
 * - 멱등성 검사 + 레코드 생성을 원자적으로 수행
 * - 이 트랜잭션은 빠르게 커밋되어 DB 커넥션을 즉시 반환
 */
@Transactional
fun createOrGetCharge(userId: UUID, amount: Long, idempotencyKey: String): ChargeCreationResult {
    val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)

    if (existing != null) {
        return when (existing.status) {
            COMPLETED -> ChargeCreationResult.AlreadyCompleted(existing)
            FAILED    -> ChargeCreationResult.PreviouslyFailed(existing)
            else      -> ChargeCreationResult.InProgress(existing)
        }
    }

    val charge = Charge(userId = userId, idempotencyKey = idempotencyKey, amount = amount)

    return try {
        chargeRepository.saveAndFlush(charge)
        ChargeCreationResult.Created(charge)
    } catch (e: DataIntegrityViolationException) {
        // 동시 요청에 의한 UNIQUE 제약조건 위반 → 기존 레코드 재조회
        val conflict = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
            ?: throw IllegalStateException("Charge not found after unique violation")
        handleExistingCharge(conflict, amount)
    }
}

/**
 * 오케스트레이터: 트랜잭션 밖에서 전체 흐름 제어
 */
fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
    // Step 1: Charge 선점 (트랜잭션 1)
    val result = createOrGetCharge(userId, amount, idempotencyKey)

    // 이미 완료된 요청이면 즉시 반환 (멱등성)
    if (result is ChargeCreationResult.AlreadyCompleted) {
        return result.toResponse()
    }

    // Step 2: PG 호출 (트랜잭션 밖 - DB 커넥션 미점유)
    val pgResult = pgClient.approve(idempotencyKey, amount)

    // Step 3: 결과 반영 (트랜잭션 2)
    return completeCharge(result.charge, pgResult, userId, amount)
}

/**
 * 트랜잭션 2: PG 결과 반영 + 잔액 증가 + 원장 기록
 */
@Transactional
fun completeCharge(charge: Charge, pgResult: PgApproveResult, ...): ChargeResponse {
    // 상태 검증: REQUESTED 상태인 경우에만 진행
    val current = chargeRepository.findById(charge.id)
        .orElseThrow { ... }

    if (current.status != ChargeStatus.REQUESTED) {
        // 이미 다른 요청에서 처리됨 → 현재 결과 반환
        return handleExistingCharge(current, amount)
    }

    if (!pgResult.success) {
        current.status = ChargeStatus.FAILED
        chargeRepository.save(current)
        throw PgPaymentFailedException(pgResult.errorCode ?: "UNKNOWN")
    }

    current.pgTransactionId = pgResult.pgTransactionId
    current.status = ChargeStatus.COMPLETED
    walletRepository.addBalance(userId, amount)
    pointLedgerRepository.save(...)
    chargeRepository.save(current)

    return ChargeResponse(...)
}
```

### 3.5 레이스 컨디션 시나리오별 분석 (개선 후)

#### 시나리오 A: 정상 재시도 (최초 요청 완료 후 재요청)

```
Request A: createOrGetCharge → Created → PG 호출 → completeCharge → COMPLETED
Request B: createOrGetCharge → AlreadyCompleted → 기존 결과 반환 ✅
```

#### 시나리오 B: 동시 요청 (최초 요청 진행 중 재요청)

```
시간  Request A                              Request B
─────────────────────────────────────────────────────────────
T1   createOrGetCharge()
T2   SELECT → null
T3   INSERT (REQUESTED) → COMMIT           createOrGetCharge()
T4   ─── PG 호출 (트랜잭션 밖) ───          SELECT → REQUESTED 발견!
T5                                          → InProgress 반환
T6                                          → 409 Conflict 응답
T7   PG 응답 → completeCharge()
T8   COMPLETED
```

**핵심 차이**: 트랜잭션 1이 빠르게 커밋되므로, Request B의 SELECT에서 REQUESTED 상태의 레코드를 확인할 수 있다. 더 이상 "보이지 않는" 레코드 문제가 발생하지 않는다.

#### 시나리오 C: 극단적 동시 요청 (INSERT 충돌)

```
시간  Request A                              Request B
─────────────────────────────────────────────────────────────
T1   SELECT → null                          SELECT → null
T2   INSERT → 성공 (COMMITTED)              INSERT → unique_violation
T3                                          catch → 재조회 → REQUESTED 발견
T4                                          → InProgress 반환 (409)
T5   PG 호출 → completeCharge
```

`DataIntegrityViolationException`을 명시적으로 catch하여 안전하게 처리한다.

#### 시나리오 D: PG 호출 중 서버 장애 (유령 승인 방지)

```
시간  Request A                              DB              PG
─────────────────────────────────────────────────────────────────
T1   INSERT (REQUESTED) → COMMITTED         REQUESTED 저장
T2   PG 호출 시작                                            승인 처리 중
T3   ★ 서버 크래시                                           승인 완료
─────────────────────────────────────────────────────────────────
     서버 재시작 후 Request B (재시도)
T4   SELECT → REQUESTED 발견!
T5   → InProgress 반환 (409)
     OR
     → 복구 로직에서 PG 상태 조회 후 처리
```

**트랜잭션 분리의 효과**: INSERT가 이미 커밋되었으므로, 서버가 크래시해도 REQUESTED 상태의 레코드가 DB에 남아있다. 재시도 요청이 와도 "기존 진행 중" 상태를 감지할 수 있어 중복 PG 호출을 방지한다.

---

## 4. 추가 방어 계층

### 4.1 PG 호출 타임아웃 설정

현재 `MockPgClient`에는 호출 측 타임아웃이 없다. 실제 환경에서는 반드시 설정해야 한다.

```kotlin
// PG 호출 시 타임아웃을 명시적으로 설정
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

### 4.2 REQUESTED 상태 만료 처리

REQUESTED 상태로 오래 남아있는 레코드는 "유령 승인" 가능성이 있다. 스케줄러로 처리한다.

```kotlin
// 일정 시간이 지난 REQUESTED 상태 Charge를 정리
@Scheduled(fixedDelay = 60_000)  // 1분마다 실행
fun cleanupStaleCharges() {
    val threshold = Instant.now().minus(Duration.ofMinutes(5))
    val staleCharges = chargeRepository.findByStatusAndCreatedAtBefore(
        ChargeStatus.REQUESTED, threshold
    )

    staleCharges.forEach { charge ->
        // PG에 해당 거래 상태 조회 (있다면)
        // → 승인되어 있으면 completeCharge 호출
        // → 없으면 FAILED로 마킹
        charge.status = ChargeStatus.FAILED
        charge.updatedAt = Instant.now()
        chargeRepository.save(charge)
    }
}
```

### 4.3 클라이언트 재시도 가이드라인

| 항목 | 권장 값 |
|------|---------|
| 재시도 시 동일 Idempotency-Key 사용 | 필수 |
| 최대 재시도 횟수 | 3회 |
| 재시도 간격 | Exponential Backoff (1s → 2s → 4s) |
| 409 Conflict 수신 시 | 일정 시간 후 상태 조회 API로 확인 |

---

## 5. 개선 전후 비교

### 5.1 보호 범위 비교

| 시나리오 | AS-IS | TO-BE |
|----------|-------|-------|
| 완료 후 재시도 | ✅ 멱등성 반환 | ✅ 멱등성 반환 |
| 진행 중 재시도 (SELECT 시점) | ⚠️ 트랜잭션 미커밋 시 감지 불가 | ✅ REQUESTED 상태 감지 가능 |
| 동시 INSERT 충돌 | ❌ 500 에러 (미처리 예외) | ✅ catch 후 안전 처리 |
| PG 지연 중 트랜잭션 롤백 | ❌ 유령 승인 → 중복결제 | ✅ REQUESTED 레코드 잔존 → 중복 방지 |
| DB 커넥션 풀 고갈 | ❌ PG 대기 중 커넥션 점유 | ✅ PG 호출 시 커넥션 미점유 |
| 서버 크래시 복구 | ❌ 기록 없음 (롤백됨) | ✅ REQUESTED 레코드로 복구 가능 |

### 5.2 트랜잭션 점유 시간 비교

```
AS-IS: ████████████████████████████████████ (PG 응답까지 전체 점유)
        SELECT  INSERT  [── PG 호출 ──]  UPDATE  COMMIT

TO-BE: ████                    ████████
       트랜잭션1              트랜잭션2
       (SELECT+INSERT)       (UPDATE+잔액+원장)
              [── PG 호출 ──]
              (커넥션 미점유)
```

---

## 6. 시퀀스 다이어그램 (개선 후 전체 흐름)

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

## 7. 구현 체크리스트

- [ ] `ChargeService.chargePoints()` 트랜잭션 분리 (3단계)
- [ ] `DataIntegrityViolationException` 핸들링 추가
- [ ] PG 호출 타임아웃 래퍼 구현
- [ ] REQUESTED 상태의 재시도 처리 로직 (409 Conflict 반환)
- [ ] REQUESTED 상태 만료 스케줄러 구현
- [ ] 동시성 테스트 작성 (Testcontainers 기반)
- [ ] 부하 테스트에 타임아웃 재시도 시나리오 추가
