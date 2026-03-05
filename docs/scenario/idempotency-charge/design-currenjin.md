# Payment - Idempotency

## Scenario 
1) 중복 충전(멱등성) + PG 승인 지연

상황

클라이언트가 /charges 요청 후 타임아웃
재시도로 같은 요청이 3번 들어옴
PG Mock은 첫 요청은 “승인됐지만 응답이 늦게” 옴 (timeout 유발)


토론 포인트

멱등 키(요청 단위) 설계
Charge 상태 머신(REQUESTED/APPROVED/COMPLETED/FAILED)
“승인됐는데 우리 시스템은 실패로 본” 케이스 복구
at-least-once 환경에서 중복 반영 방지


구현 검증(테스트)

같은 Idempotency-Key로 3번 호출해도 잔액 1번만 증가
“승인 지연” 상황에서 재호출 시 동일 결과 반환
:point_right: 첫 주제로 가장 추천. (결제/분산의 본질)

## Solution

### 1. 현재 코드 분석

#### 1.1 현재 충전 흐름

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

#### 1.2 현재 방어 로직

| 방어 로직 | 구현 | 보호 범위 |
|-----------|------|----------|
| DB UNIQUE 제약 | `(user_id, idempotency_key)` | 동일 키로 2건 INSERT 차단 |
| SELECT 검사 | `findByUserIdAndIdempotencyKey` | 완료된 요청 재반환 |
| 상태 기반 분기 | `handleExistingCharge` | COMPLETED/FAILED/진행중 응답 구분 |
| Wallet 원자적 업데이트 | `UPDATE ... SET balance = balance + :amount` | 잔액 갱신 시 원자성 유지 |

#### 1.3 문제점: 동시 재시도 타임라인

PG delay 2초, 클라이언트 timeout 1초 가정:

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

t=2.0s  [Req 1] PG 응답 수신 → 잔액 증가 → COMPLETED → COMMIT

t=2.0s  [Req 2] INSERT 재개 → unique_violation → DataIntegrityViolationException → 500 ❌
t=2.0s  [Req 3] INSERT 재개 → unique_violation → DataIntegrityViolationException → 500 ❌
```

#### 1.4 식별된 문제 4가지

**P1. 긴 트랜잭션 → DB 커넥션 고갈**

PG 호출이 `@Transactional` 안에 있어, 응답까지 DB 커넥션을 점유한다.
동시 요청이 몰리면 커넥션 풀이 고갈되어 전체 서비스가 마비된다.

```
@Transactional 범위
┌───────────────────────────────────────────────────────┐
│ SELECT → INSERT → [ PG 호출 2초 대기 ] → UPDATE → COMMIT │  ← 커넥션 2초+ 점유
└───────────────────────────────────────────────────────┘
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

#### 1.5 근본 원인

**외부 I/O 호출(PG)이 트랜잭션 안에 묶여 있다.**

이 하나의 원인이 P1~P4 모든 문제를 만들어낸다.

---

### 2. 해결: 트랜잭션 분리

#### 2.1 핵심 원칙

> 외부 I/O(PG 호출)를 트랜잭션 밖으로 분리하여, DB 커넥션 점유를 최소화하고 충전 상태를 단계별로 관리한다.

#### 2.2 AS-IS vs TO-BE

```
AS-IS: 단일 트랜잭션
┌──────────────────────────────────────────────────────┐
│ SELECT → INSERT → [── PG 호출 ──] → UPDATE → COMMIT  │
└──────────────────────────────────────────────────────┘
                     ↑ DB 커넥션 점유 상태로 외부 호출

TO-BE: 트랜잭션 분리
┌────────────────────┐                    ┌──────────────────────────┐
│ TX-1: INSERT        │                    │ TX-2: 상태 변경 + 잔액    │
│ (REQUESTED) → COMMIT│ → [PG 호출] →     │ + 원장 → COMPLETED       │
│ (~10ms)             │   (커넥션 미점유)   │ (~10ms)                  │
└────────────────────┘                    └──────────────────────────┘
```

#### 2.3 상태 머신

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

#### 2.4 의사 코드

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

#### 2.5 멱등성 응답 전략

| Charge 상태 | 조건 | HTTP 응답 | 설명 |
|-------------|------|-----------|------|
| — | amount 불일치 | **422** | 같은 멱등키에 다른 금액 → 잘못된 요청 |
| REQUESTED / PG_APPROVED | amount 일치 | **409** | 이전 요청이 아직 처리 중 |
| COMPLETED | amount 일치 | **200** | 원래 성공 응답을 그대로 반환 |
| FAILED | amount 일치 | **409** | 실패한 건. 새 멱등키로 재시도 안내 |

---

### 3. 개선 후 시나리오 검증

PG delay 2초, 클라이언트 timeout 1초, 동일 멱등키로 3회 요청:

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

#### 문제별 해결 여부

| 문제 | AS-IS | TO-BE | 해결 |
|------|-------|-------|------|
| P1. 커넥션 고갈 | PG 2초간 TX 열림 | TX-1 ~10ms, TX-2 ~10ms | O |
| P2. 재시도 500 | `DataIntegrityViolationException` 미처리 | catch 후 기존 charge 조회 → 409/200 | O |
| P3. 재시도 블로킹 | INSERT row-lock에서 2초 대기 | TX-1 이미 COMMIT → 즉시 조회 | O |
| P4. 유령 승인 | TX 롤백 시 charge 삭제 → 중복 PG 승인 | INSERT 이미 COMMIT → REQUESTED 잔존 → 중복 방지 | O |

---

### 4. "승인됐는데 실패로 본" 케이스 복구

PG는 승인했지만, TX-2(결과 반영) 단계에서 서버 장애가 발생하면 charge가 REQUESTED 상태로 남는다.
이 경우 DB에 REQUESTED 레코드가 존재하므로 재시도 시 409로 응답되고, 중복 PG 승인은 방지된다.
하지만 잔액이 반영되지 않은 채 stuck 되는 문제가 있다.

#### 복구 방안: REQUESTED 상태 만료 스케줄러

```kotlin
@Scheduled(fixedDelay = 60_000)  // 1분마다 실행
fun cleanupStaleCharges() {
    val threshold = Instant.now().minus(Duration.ofMinutes(5))
    val staleCharges = chargeRepository.findByStatusAndCreatedAtBefore(
        ChargeStatus.REQUESTED, threshold
    )

    staleCharges.forEach { charge ->
        // TODO: PG 상태 조회 API가 있다면 실제 승인 여부 확인 후 처리
        // - PG 승인됨 → completeCharge() 호출하여 잔액 반영
        // - PG 승인 안 됨 → FAILED 마킹
        charge.status = ChargeStatus.FAILED
        charge.updatedAt = Instant.now()
        chargeRepository.save(charge)
    }
}
```

실제 운영 환경에서는 PG 상태 조회 API를 통해 승인 여부를 확인한 후 분기하는 것이 이상적이다.
현재 MockPgClient 환경에서는 5분 초과 시 FAILED 처리하는 방식으로 구현할 수 있다.

---

### 5. 구현 검증 (테스트)

#### 5.1 같은 Idempotency-Key로 3번 호출해도 잔액 1번만 증가

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

#### 5.2 승인 지연 상황에서 재호출 시 동일 결과 반환

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

#### 5.3 k6 부하 테스트 검증 항목

기존 `load-test/scenarios/idempotency/test.js`의 검증 기준:

| 메트릭 | 기대값 |
|--------|--------|
| `duplicate_charges` (중복 충전 수) | 0 |
| `balance_correct_rate` (잔액 정확도) | > 95% |
| 응답 패턴 | 200+409 (정상) 또는 200+200 (멱등) |
] k6 멱등성 시나리오 부하 테스트 실행 및 검증

