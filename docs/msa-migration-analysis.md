# MSA 전환 분석

## 1. 현재 아키텍처 요약

포인트 충전 / 기프티콘 구매 / 기프티콘 사용을 처리하는 모놀리스 시스템.

```
Controller → Service → Repository → PostgreSQL (단일 DB)
```

### 도메인 모델

```
User ──1:1── Wallet (balance, @Version 낙관적 락)

Charge (충전 요청)
  - (userId, idempotencyKey) unique constraint
  - status: REQUESTED → PG_APPROVED → COMPLETED / FAILED

GifticonProduct (상품) ──1:N── Gifticon (기프티콘)
  - status: ISSUED → CONSUMED

PointLedger (포인트 원장)
  - type: CHARGE(+) / PURCHASE(-)
```

### 핵심 트랜잭션 구조

| 플로우 | 트랜잭션 범위 | 관련 엔티티 |
|--------|-------------|------------|
| 충전 (ChargeService) | TX-1: Charge 선점 → TX 밖: PG 호출 → TX-2: Charge + Wallet + Ledger | Charge, Wallet, PointLedger |
| 구매 (GifticonService) | 단일 TX: Wallet 차감 + Gifticon 발급 + Ledger 기록 | Wallet, Gifticon, PointLedger |
| 사용 (GifticonService) | 단일 TX: 조건부 UPDATE | Gifticon |

### 동시성 보호 방식

| 시나리오 | 보호 방식 |
|----------|-----------|
| 중복 충전 | `(userId, idempotencyKey)` unique constraint + 상태 머신 |
| 잔액 경합 | `UPDATE wallet SET balance = balance - :price WHERE balance >= :price` |
| 기프티콘 중복 사용 | `UPDATE gifticon SET status = 'CONSUMED' WHERE status = 'ISSUED'` |

---

## 2. 서비스 경계 후보

도메인 응집도 기준 3개 서비스로 분리 가능.

```
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  Charge 서비스   │  │  Wallet 서비스   │  │ Gifticon 서비스  │
│                 │  │                 │  │                 │
│ Charge          │  │ User            │  │ GifticonProduct │
│ PgClient        │  │ Wallet          │  │ Gifticon        │
│                 │  │ PointLedger     │  │                 │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

| 서비스 | 엔티티 | 현재 코드 |
|--------|--------|-----------|
| Charge 서비스 | Charge | ChargeService, ChargeController, PgClient |
| Wallet 서비스 | User, Wallet, PointLedger | UserService, WalletRepository |
| Gifticon 서비스 | Gifticon, GifticonProduct | GifticonService, ProductService |

---

## 3. 서비스 간 커플링 분석

MSA 전환에서 가장 큰 문제는 **Wallet이 모든 핵심 플로우의 중심**에 있다는 점이다.

### 3-1. ChargeService → WalletRepository 직접 의존

`ChargeService.completeCharge()`에서 하나의 트랜잭션 안에서:

```kotlin
// TX-2 내부 (ChargeService.kt:109-167)
chargeRepository.save(charge)          // Charge 도메인
walletRepository.addBalance(...)       // Wallet 도메인 ← 다른 서비스
pointLedgerRepository.save(...)        // Wallet 도메인 ← 다른 서비스
```

Charge 서비스가 Wallet 서비스의 DB를 직접 수정하고 있다.

### 3-2. GifticonService → WalletRepository 직접 의존

`GifticonService.purchaseGifticon()`에서 하나의 트랜잭션 안에서:

```kotlin
// 단일 TX 내부 (GifticonService.kt:30-68)
walletRepository.subtractBalance(...)  // Wallet 도메인 ← 다른 서비스
gifticonRepository.save(...)           // Gifticon 도메인
pointLedgerRepository.save(...)        // Wallet 도메인 ← 다른 서비스
```

기프티콘 구매 시 잔액 차감과 기프티콘 발급이 원자적으로 처리되고 있다.

### 3-3. UserService → WalletRepository 의존

```kotlin
// UserService.kt:22-27
userRepository.save(user)              // User 도메인
walletRepository.save(Wallet(...))     // Wallet 도메인 ← 같은 서비스이긴 하나 주의
```

User와 Wallet을 같은 서비스에 두지 않는다면 이 부분도 분리 대상.

---

## 4. 분리 시 깨지는 것들

### 4-1. 분산 트랜잭션 문제

| 현재 (모놀리스) | MSA 전환 후 문제 |
|----------------|-----------------|
| `completeCharge`: Charge 상태 변경 + 잔액 증가 + 원장 기록이 하나의 TX | Charge는 COMPLETED인데 잔액 증가가 실패할 수 있음 |
| `purchaseGifticon`: 잔액 차감 + 기프티콘 발급이 하나의 TX | 잔액은 차감됐는데 기프티콘 발급이 실패하거나, 그 반대 상황 |
| `subtractBalance`의 `WHERE balance >= :amount` 조건부 UPDATE | 동기 API 호출로 변경 시 잔액 확인→차감 사이에 race condition 발생 가능 |

### 4-2. 데이터 정합성 보호 장치 약화

- **Wallet의 `@Version` 낙관적 락**: 서비스 분리 시 Wallet 서비스 내부에서만 의미 있음
- **Charge의 멱등성**: PG 호출 후 Wallet 잔액 증가가 별도 서비스 호출이 되면 멱등성 보장 범위가 달라짐
- **PointLedger**: 현재 Charge/Purchase와 같은 TX에서 기록되어 정확한데, 분리 시 원장 누락 가능성

---

## 5. 해결 전략

### 5-1. Saga 패턴

#### 충전 플로우 (Orchestration Saga)

```
Charge 서비스                         Wallet 서비스
    │                                     │
    ├─ Charge 생성 (REQUESTED)            │
    ├─ PG 호출                            │
    ├─ Charge 상태 → PG_APPROVED          │
    ├─ BalanceAddRequest 발행 ──────────→ │
    │                                     ├─ 잔액 증가
    │                                     ├─ 원장 기록
    │  ←────────────── BalanceAdded 응답 ──┤
    ├─ Charge 상태 → COMPLETED            │
    │                                     │
    │  [실패 시]                           │
    │  ←─────────── BalanceAddFailed ──────┤
    ├─ Charge 상태 → FAILED               │
    ├─ PG 취소 요청                        │
```

#### 구매 플로우 (Choreography Saga)

```
Gifticon 서비스                       Wallet 서비스
    │                                     │
    ├─ DeductBalanceRequest 발행 ────────→ │
    │                                     ├─ 잔액 차감 (WHERE balance >= amount)
    │                                     ├─ 원장 기록
    │  ←──────────── BalanceDeducted ──────┤
    ├─ 기프티콘 발급                        │
    │                                     │
    │  [기프티콘 발급 실패 시 - 보상 트랜잭션]  │
    ├─ RefundBalanceRequest 발행 ─────────→│
    │                                     ├─ 잔액 복원
```

### 5-2. Outbox 패턴

이벤트 발행의 원자성 보장을 위해 도메인 변경과 이벤트를 같은 TX에서 저장.

```
┌──────────────────────────────────┐
│ TX                               │
│  charge.status = COMPLETED       │
│  outbox.save(ChargeCompletedEvent)│
└──────────────────────────────────┘
         │
    CDC / Polling
         │
         ▼
   Message Broker (Kafka 등)
         │
         ▼
   Wallet 서비스: 잔액 증가
```

### 5-3. 잔액 차감 동시성 보호 재설계

현재 `subtractBalance`의 `WHERE balance >= :amount`는 DB 레벨 원자적 연산이다. MSA에서의 대안:

| 방식 | 설명 | 장단점 |
|------|------|--------|
| Wallet 서비스 내부 API | Wallet 서비스가 잔액 차감 API를 제공, 내부에서 동일한 원자적 UPDATE 유지 | 단순하지만 동기 호출 의존 |
| 잔액 예약(Reservation) | 차감 전 잔액을 임시 홀드, 확정/취소 2단계 | 복잡하지만 안전 |
| 이벤트 + 멱등 처리 | 차감 요청을 이벤트로 발행, Wallet 서비스가 멱등하게 처리 | 비동기이므로 즉시 응답 불가 |

### 5-4. 멱등성 범위 재정의

현재 Charge의 멱등성은 "충전 + 잔액 증가"까지 한 번에 보장된다. 분리 시:

- **Charge 서비스**: `idempotencyKey`로 Charge 생성의 멱등성 보장
- **Wallet 서비스**: `chargeId`를 기준으로 잔액 증가의 멱등성을 별도 보장 (중복 이벤트 소비 방지)

---

## 6. 분리 난이도 분석

### 비교적 쉬움

| 대상 | 이유 |
|------|------|
| ProductService | `GifticonProductRepository` 하나에만 의존. 다른 서비스와 트랜잭션 공유 없음 |
| PgClient | 이미 인터페이스로 추상화. 별도 서비스나 외부 연동으로 전환 용이 |
| Gifticon 사용(consume) | Gifticon 테이블만 조건부 UPDATE. 다른 서비스 의존 없음 |

### 주의 필요

| 대상 | 이유 |
|------|------|
| PointLedger | Charge와 Purchase 양쪽에서 기록. Wallet 서비스로 이동 후 이벤트 수신으로 전환 필요 |
| User-Wallet 1:1 생성 | 같은 TX에서 User + Wallet 생성. 분리 시 이벤트 기반으로 변경 필요 |
| ChargeService 3-phase | 이미 TX 분리가 되어 있어 Saga 전환에 유리하나, TX-2의 원자성 재설계 필요 |

### 가장 어려움

| 대상 | 이유 |
|------|------|
| GifticonService.purchaseGifticon | 잔액 차감 + 기프티콘 발급이 단일 TX. 보상 트랜잭션 설계 필수 |
| Wallet 잔액 정합성 | 모든 플로우의 중심. 분산 환경에서 동시성 보호 재설계 필요 |

---

## 7. 선행 작업 (모듈러 모놀리스 단계)

MSA로 바로 전환하기보다, 모놀리스 안에서 먼저 서비스 경계를 분리하는 단계를 거치는 것을 권장한다.

### Step 1. 서비스 간 의존을 인터페이스로 추상화

GifticonService가 WalletRepository를 직접 쓰는 대신 WalletService 인터페이스를 통하도록 변경.

```kotlin
// Before
class GifticonService(
    private val walletRepository: WalletRepository,    // Repository 직접 의존
    private val pointLedgerRepository: PointLedgerRepository
)

// After
class GifticonService(
    private val walletService: WalletService           // 인터페이스 의존
)
```

ChargeService도 동일하게 WalletService 인터페이스를 통해 잔액을 조작하도록 변경.

### Step 2. 도메인 이벤트 도입

Spring ApplicationEvent를 활용하여 모놀리스 안에서 이벤트 기반으로 전환.

```kotlin
// 충전 완료 시
applicationEventPublisher.publishEvent(ChargeCompletedEvent(chargeId, userId, amount))

// Wallet 서비스에서 수신
@TransactionalEventListener(phase = BEFORE_COMMIT)
fun onChargeCompleted(event: ChargeCompletedEvent) {
    walletRepository.addBalance(event.userId, event.amount)
    pointLedgerRepository.save(...)
}
```

나중에 Spring ApplicationEvent → 메시지 브로커로 교체.

### Step 3. DB 스키마 분리

같은 PostgreSQL 인스턴스 안에서 서비스별 스키마로 분리.

```
PostgreSQL
├── charge_schema:  charges
├── wallet_schema:  users, wallets, point_ledgers
└── gifticon_schema: gifticon_products, gifticons
```

cross-schema 조인이 없는지 검증하여 서비스 간 데이터 독립성 확인.

### Step 4. 서비스 간 API 규격 정의

현재 컨트롤러는 외부 API용. 서비스 간 내부 통신 규격이 추가로 필요.

```
Wallet 서비스 내부 API:
  POST /internal/wallets/{userId}/add-balance
  POST /internal/wallets/{userId}/deduct-balance
  POST /internal/wallets/{userId}/refund
```

### Step 5. 물리적 서비스 분리

위 단계가 완료되면 각 모듈을 독립 서비스로 배포.

```
Step 1-2: 논리적 경계 분리 (모듈러 모놀리스)
Step 3:   데이터 독립성 검증
Step 4:   통신 규격 정의
Step 5:   물리적 분리 + 인프라 (서비스 디스커버리, API Gateway, 메시지 브로커)
```

---

## 8. 결론

- **핵심 과제**: Wallet(잔액)이 모든 플로우의 중심이므로, Wallet 서비스의 독립성과 멱등성 보장이 MSA 성공의 열쇠
- **현재 강점**: ChargeService의 3-phase 구조가 이미 Saga와 유사하여 전환에 유리
- **권장 접근**: 모놀리스 안에서 모듈러 모놀리스 단계를 먼저 거친 후, 점진적으로 물리적 분리 진행
- **가장 먼저 분리 가능한 서비스**: ProductService (의존성 최소)
- **가장 신중해야 할 부분**: 기프티콘 구매 플로우 (잔액 차감 + 발급의 원자성)
