# 충전 멱등성 개선 설계

## 1. 문제 시나리오

클라이언트가 charge API 요청 후 타임아웃이 발생하여, 동일한 idempotency key로 재시도 요청이 3번 들어오는 상황.
PG Mock에서 승인은 성공했지만 응답이 늦게 돌아와 클라이언트 타임아웃을 유발한다.

### 1.1 타임라인 (PostgreSQL READ COMMITTED 기준)

현재 `ChargeService.chargePoints()`는 `@Transactional`로 전체가 하나의 트랜잭션이다.
PG Mock delay 2초, 클라이언트 timeout 1초 가정:

```
t=0.0s  [Request 1] BEGIN TX
t=0.0s  [Request 1] SELECT charge → NULL
t=0.0s  [Request 1] INSERT charge(REQUESTED) — 미커밋 상태
t=0.0s  [Request 1] pgClient.approve() 호출 (2초 소요)

t=0.5s  [Request 2] BEGIN TX (클라이언트 재시도)
t=0.5s  [Request 2] SELECT charge → NULL (READ COMMITTED: 미커밋 INSERT 안 보임)
t=0.5s  [Request 2] INSERT 시도 → BLOCKED (Request 1의 unique key row lock 대기)

t=1.0s  [Request 3] BEGIN TX (클라이언트 재시도)
t=1.0s  [Request 3] SELECT → NULL → INSERT 시도 → BLOCKED

t=1.0s  [Client] 타임아웃. 3개 요청 모두 응답을 받지 못한 상태.

t=2.0s  [Request 1] PG 응답 수신 (승인 성공)
t=2.0s  [Request 1] wallet 잔액 업데이트 → ledger 기록 → charge COMPLETED → COMMIT

t=2.0s  [Request 2] INSERT 재개 → UniqueConstraint 위반 → DataIntegrityViolationException → 500
t=2.0s  [Request 3] INSERT 재개 → UniqueConstraint 위반 → DataIntegrityViolationException → 500
```

### 1.2 식별된 문제

#### P1. 클라이언트가 결과를 모름
Request 1은 서버에서 정상 완료되지만, 클라이언트는 t=1.0s에 이미 타임아웃.
충전은 성공했는데 클라이언트는 성공/실패를 알 수 없다.

#### P2. 재시도 요청이 500 에러
Request 2, 3은 `DataIntegrityViolationException`을 catch하지 않아 500으로 응답.
409(Conflict)가 아닌 500이므로 클라이언트는 서버 오류로 인식한다.

#### P3. 긴 트랜잭션으로 DB 커넥션 고갈
PG 호출 2초 동안 트랜잭션이 열려 있어 DB 커넥션을 점유.
동시 요청이 많으면 커넥션 풀이 고갈되어 전체 서비스에 영향을 준다.

#### P4. 재시도 요청도 블로킹
Request 2, 3이 INSERT에서 Request 1의 TX 완료까지 대기(최대 2초).
재시도 요청도 빠르게 응답하지 못하고 불필요하게 블로킹된다.

### 1.3 근본 원인

| 근본 원인 | 영향 |
|---|---|
| 외부 API 호출이 트랜잭션 안에 있음 | 긴 TX → 커넥션 고갈, 타임아웃 |
| UniqueConstraint 예외 미처리 | 재시도 요청이 500 에러 |

핵심: **외부 API 호출이 트랜잭션 안에 묶여 있어 긴 TX가 발생하고, 동시 재시도 요청을 적절히 처리하지 못한다.**

---

## 2. 해결 방안: 2-Phase 트랜잭션 분리

### 2.1 설계 원칙

1. **외부 API 호출을 트랜잭션 밖으로 분리**하여 DB 커넥션 점유 최소화
2. **PG 승인 결과를 먼저 DB에 기록**한 후 후속 처리를 수행하여 정합성 보장
3. **UniqueConstraint를 동시성 제어의 1차 방어선**으로 활용

### 2.2 Phase 구조

```
Phase 1: [TX-1] Charge INSERT(REQUESTED) → COMMIT
   ↓  (이제 다른 TX에서 이 Charge가 보임)
Phase 2: [TX 없음] pgClient.approve() 호출
   ↓
Phase 3: [TX-2] PG 결과 반영 (PG_APPROVED → 잔액 업데이트 → COMPLETED)
```

### 2.3 상세 흐름

```
chargePoints(userId, amount, idempotencyKey)
│
├─ Phase 1: Charge 생성 [TX-1]
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
│   └─ 실패 시: [TX-2a] charge.status = FAILED → COMMIT → 예외
│
└─ Phase 3: 내부 반영 [TX-2]
    ├─ charge.status = PG_APPROVED, pgTransactionId 저장
    ├─ walletRepository.addBalance(userId, amount)
    ├─ pointLedgerRepository.save(...)
    ├─ charge.status = COMPLETED
    └─ COMMIT
```

### 2.4 이 설계가 해결하는 문제

| 문제 | 해결 |
|---|---|
| P2. 재시도 500 에러 | UniqueConstraint catch → 기존 charge 조회 후 적절한 응답 |
| P3. DB 커넥션 고갈 | PG 호출이 TX 밖 → TX 시간 = INSERT/UPDATE 시간만 |
| P4. 재시도 블로킹 | Phase 1 COMMIT이 빠르므로 재시도가 즉시 기존 charge 조회 가능 |

### 2.5 멱등성 응답 전략

원칙: **같은 요청을 여러 번 보내도 동일한 결과를 반환한다.** (Stripe, Toss Payments 등 업계 표준 패턴)

#### 상태별 응답

| charge 상태 | 조건 | 응답 | 설명 |
|---|---|---|---|
| (any) | amount 불일치 | **422** Unprocessable Entity | 같은 멱등키에 다른 파라미터 → 잘못된 요청 |
| REQUESTED / PG_APPROVED | amount 일치 | **409** Conflict | 이전 요청이 아직 처리 중 → 충돌 |
| COMPLETED | amount 일치 | **200** + 캐시된 결과 | 원래 성공 응답 그대로 반환 |
| FAILED | amount 일치 | **409** Conflict | 실패한 충전, 새 키로 재시도 안내 |

- **422**: 클라이언트의 요청 자체가 잘못됨 (멱등키 재사용 오류)
- **409**: 서버 상태와 충돌 (처리 중이거나 실패한 건)

#### 재시도 타임라인 (2-Phase 적용 후)

```
[Client]                              [Server]
  ── POST /charge (key=abc) ────→  Phase 1: INSERT(REQUESTED) COMMIT
                                   Phase 2: pgClient.approve() 시작...
  ── (timeout 1초) ──
  ── POST /charge (key=abc) ────→  UniqueConstraint catch
                                   SELECT → REQUESTED, amount 일치
                              ←──  409 "처리 중, 잠시 후 재시도"

  ── (backoff 2초 후) ──
  ── POST /charge (key=abc) ────→  UniqueConstraint catch
                                   SELECT → COMPLETED, amount 일치
                              ←──  200 { chargeId, status, balance }
```

기존 방식과의 차이:
- **기존**: 재시도가 INSERT에서 블로킹 → TX 끝날 때까지 대기 → 500 에러
- **개선**: Phase 1이 이미 COMMIT → 재시도가 즉시 기존 charge 조회 → 적절한 응답

---

## 3. 설계 검증: 기존 문제 시나리오 재현

PG Mock delay 2초, 클라이언트 timeout 1초, 동일 멱등키로 재시도 3회.

### 3.1 2-Phase 적용 후 타임라인

```
t=0.00s  [Req 1] Phase 1 [TX-1]: INSERT charge(REQUESTED) → COMMIT (~10ms)
t=0.01s  [Req 1] Phase 2: pgClient.approve() 호출 시작 (2초 소요)

t=0.50s  [Req 2] Phase 1: INSERT 시도
         → UniqueConstraint 위반 (Req 1이 이미 COMMIT, 블로킹 없음)
         → catch → SELECT charge → REQUESTED, amount 일치
         → 409 "처리 중" 즉시 응답

t=1.00s  [Req 3] Phase 1: INSERT 시도
         → UniqueConstraint 위반
         → catch → SELECT charge → REQUESTED, amount 일치
         → 409 "처리 중" 즉시 응답

t=2.01s  [Req 1] PG 응답 수신 (승인 성공)
t=2.01s  [Req 1] Phase 3 [TX-2]: PG_APPROVED → 잔액 업데이트 → COMPLETED → COMMIT
t=2.02s  [Req 1] 200 응답 (클라이언트는 이미 타임아웃이라 못 받음)

t=3.00s  [Req 4] 클라이언트 backoff 후 재시도
         → INSERT → UniqueConstraint → SELECT → COMPLETED, amount 일치
         → 200 { chargeId, status=COMPLETED, balance }
```

### 3.2 문제별 검증 결과

| 문제 | 기존 동작 | 2-Phase 적용 후 | 해결 |
|---|---|---|---|
| P1. 클라이언트가 결과를 모름 | 타임아웃 후 결과 확인 불가 | backoff 재시도 시 COMPLETED 결과 수신 (200) | O |
| P2. 재시도 500 에러 | UniqueConstraint → 500 | catch → 상태별 적절한 응답 (409/200) | O |
| P3. DB 커넥션 고갈 | PG 2초 동안 TX 열림 | TX-1 ~10ms, TX-2 ~10ms. PG 호출은 TX 밖 | O |
| P4. 재시도 블로킹 | INSERT에서 최대 2초 대기 | Phase 1 이미 COMMIT → 즉시 조회 | O |

> PG 승인 후 내부 롤백으로 인한 상태 정합성 문제(Phase 3 실패 복구, REQUESTED stuck 처리 등)는 별도로 다룬다.
