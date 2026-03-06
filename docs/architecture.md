# Gonza-Payment 전체 아키텍처

**포인트 충전 / 기프티콘 구매·소비 결제 시스템 MVP** (Kotlin + Spring Boot 3.3 + PostgreSQL)

---

## 시스템 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          CLIENT / LOAD TEST                            │
│                                                                         │
│   ┌──────────┐   ┌──────────────┐   ┌────────────────┐                 │
│   │  k6 Load │   │ k6 Idempot.  │   │ k6 Balance-Race│                 │
│   │   Test   │   │    Test      │   │     Test       │                 │
│   └────┬─────┘   └──────┬───────┘   └───────┬────────┘                 │
│        └────────────────┬┘───────────────────┘                          │
└─────────────────────────┼───────────────────────────────────────────────┘
                          │ HTTP (REST)
                          ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                        SPRING BOOT APPLICATION                          │
│                        (api container :8080)                             │
│                                                                         │
│  ┌─────────────────────── Controller Layer ───────────────────────────┐ │
│  │                                                                     │ │
│  │  UserController      ChargeController     ProductController        │ │
│  │  POST /api/users     POST /api/wallets/   POST /api/products       │ │
│  │  GET  /api/users/      {userId}/charges   GET  /api/products       │ │
│  │    {userId}/wallet   (+ Idempotency-Key                            │ │
│  │                        Header)            GifticonController       │ │
│  │                                           POST /api/users/         │ │
│  │                                             {userId}/gifticons     │ │
│  │                                           POST .../consume         │ │
│  └───────────┬─────────────┬──────────────────────┬───────────────────┘ │
│              │             │                      │                      │
│              ▼             ▼                      ▼                      │
│  ┌─────────────────────── Service Layer ──────────────────────────────┐ │
│  │                                                                     │ │
│  │  UserService        ChargeService          GifticonService         │ │
│  │  - createUser()     - charge() ◄──────┐   - purchaseGifticon()    │ │
│  │  - getWallet()      │                  │   - consumeGifticon()     │ │
│  │                     │  3-Phase Pattern  │                           │ │
│  │                     │                  │   ProductService           │ │
│  │                     ▼                  │   - createProduct()        │ │
│  │              ┌──────────────┐          │   - getAllProducts()       │ │
│  │              │ Phase 1 (TX) │          │                            │ │
│  │              │ createOrGet  │          │                            │ │
│  │              │   Charge     │          │                            │ │
│  │              └──────┬───────┘          │                            │ │
│  │                     │                  │                            │ │
│  │              ┌──────▼───────┐          │                            │ │
│  │              │ Phase 2 (IO) │──────────┼──────► PG Mock Client     │ │
│  │              │ pgClient     │          │        - approve()         │ │
│  │              │  .approve()  │          │        - delay-ms config   │ │
│  │              └──────┬───────┘          │        - fail-rate config  │ │
│  │                     │                  │                            │ │
│  │              ┌──────▼───────┐          │                            │ │
│  │              │ Phase 3 (TX) │          │                            │ │
│  │              │ complete     │──────────┘                            │ │
│  │              │   Charge     │                                       │ │
│  │              └──────────────┘                                       │ │
│  └───────────┬─────────────┬──────────────────────┬───────────────────┘ │
│              │             │                      │                      │
│              ▼             ▼                      ▼                      │
│  ┌─────────────────────── Repository Layer ───────────────────────────┐ │
│  │                                                                     │ │
│  │  UserRepository    ChargeRepository       GifticonRepository       │ │
│  │                    - findByUserIdAnd       - consumeById()          │ │
│  │  WalletRepository    IdempotencyKey()       (WHERE status=ISSUED)  │ │
│  │  - addBalance()                                                     │ │
│  │    (atomic SQL)    PointLedgerRepository   GifticonProductRepo     │ │
│  │  - subtractBalance()                      - findAllByIsActiveTrue()│ │
│  │    (WHERE bal>=amt)                                                 │ │
│  └────────────────────────────┬───────────────────────────────────────┘ │
│                               │                                         │
│  ┌──── Exception Layer ─────┐ │  ┌──── DTO Layer ──────────────────┐   │
│  │ GlobalExceptionHandler   │ │  │ ChargeRequest/Response          │   │
│  │ - NotFoundException  404 │ │  │ PurchaseGifticonRequest/Resp    │   │
│  │ - ConflictException  409 │ │  │ ConsumeGifticonResponse         │   │
│  │ - InsufficientBal.  400 │ │  │ CreateUserRequest/Response      │   │
│  │ - Unprocessable     422 │ │  │ WalletResponse                  │   │
│  │ - PgPaymentFailed   502 │ │  │ CreateProductRequest/Response   │   │
│  │ - AlreadyConsumed   409 │ │  │ ProductResponse                 │   │
│  └──────────────────────────┘ │  └─────────────────────────────────┘   │
└───────────────────────────────┼─────────────────────────────────────────┘
                                │ JDBC
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                       PostgreSQL 16 (db :5432)                          │
│                                                                         │
│  ┌──────────┐ ┌──────────┐ ┌───────────────┐ ┌──────────────────────┐  │
│  │  users   │ │  wallet  │ │    charge     │ │  gifticon_product    │  │
│  │──────────│ │──────────│ │───────────────│ │──────────────────────│  │
│  │ id (PK)  │ │userId(PK)│ │ id (PK)      │ │ id (PK)              │  │
│  │ name     │ │ balance  │ │ userId (FK)   │ │ name                 │  │
│  │ createdAt│ │ version  │ │ idempotencyKey│ │ price                │  │
│  └────┬─────┘ └────┬─────┘ │ amount       │ │ isActive             │  │
│       │  1:1       │       │ status       │ └──────────┬───────────┘  │
│       └────────────┘       │ pgTxId       │            │              │
│       │                    │ UQ(userId,   │     ┌──────┴───────────┐  │
│       │                    │  idempKey)   │     │    gifticon      │  │
│       │ 1:N                └──────────────┘     │─────────────────-│  │
│       │                          │              │ id (PK)          │  │
│       │                          │              │ userId (FK)      │  │
│       │                    ┌─────┴──────────┐   │ productId (FK)   │  │
│       │                    │ point_ledger   │   │ code (UQ)        │  │
│       └────────────────────│────────────────│   │ status           │  │
│                            │ id (PK)       │   │ issuedAt         │  │
│                            │ userId (FK)   │   │ consumedAt       │  │
│                            │ type          │   └──────────────────┘  │
│                            │ refId         │                          │
│                            │ amountDelta   │                          │
│                            │ createdAt     │                          │
│                            └───────────────┘                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 디렉토리 구조

```
gonza-payment/
├── src/main/kotlin/com/gonza/payment/
│   ├── domain/              # JPA 엔티티 & Enum
│   ├── repository/          # Spring Data JPA 리포지토리
│   ├── service/             # 비즈니스 로직 계층
│   ├── controller/          # REST API 엔드포인트
│   ├── dto/                 # Request/Response DTO
│   ├── pg/                  # PG(결제대행) Mock 클라이언트
│   ├── exception/           # 커스텀 예외 & 글로벌 핸들러
│   └── GonzaPaymentApplication.kt
├── src/main/resources/
│   ├── application.yml          # 기본 설정
│   └── application-docker.yml   # Docker 프로필 설정
├── src/test/kotlin/com/gonza/payment/
│   ├── service/             # 서비스 단위 테스트
│   ├── repository/          # 리포지토리 통합 테스트
│   ├── controller/          # API 통합 테스트 (MockMvc)
│   └── concurrency/         # 동시성 테스트 (Testcontainers)
├── load-test/               # k6 부하 테스트
│   ├── common/              # 공통 시드/스냅샷 스크립트
│   ├── scenarios/
│   │   ├── load/            # 부하 테스트 시나리오
│   │   ├── idempotency/     # 멱등성 시나리오
│   │   └── balance-race/    # 잔액 경쟁 시나리오
│   └── results/             # 테스트 결과 & 리포트
├── docs/                    # 문서
├── build.gradle.kts         # Gradle 빌드 설정
├── docker-compose.yml       # Docker Compose 스택
├── Dockerfile               # 애플리케이션 이미지
└── run-test.sh              # 시나리오 실행 스크립트
```

---

## 핵심 비즈니스 플로우

### 1. 포인트 충전 (3-Phase Pattern)

```
Client                    ChargeService              DB                  PG (외부)
  │                            │                      │                     │
  │── POST /charges ──────────►│                      │                     │
  │   + Idempotency-Key        │                      │                     │
  │                            │                      │                     │
  │                     ┌──────┴──────┐               │                     │
  │                     │ Phase 1 (TX)│               │                     │
  │                     │ INSERT      │──── INSERT ──►│                     │
  │                     │ charge      │    charge     │                     │
  │                     │ (REQUESTED) │◄── OK/DUP ───│                     │
  │                     └──────┬──────┘               │                     │
  │                            │  ※ DUP → return 409  │                     │
  │                            │                      │                     │
  │                     ┌──────┴──────┐               │                     │
  │                     │ Phase 2(I/O)│               │                     │
  │                     │ DB 커넥션   │──── approve() ────────────────────►│
  │                     │ 미점유      │◄── pgTxId ───────────────────────-─│
  │                     └──────┬──────┘               │                     │
  │                            │                      │                     │
  │                     ┌──────┴──────┐               │                     │
  │                     │ Phase 3 (TX)│               │                     │
  │                     │ UPDATE      │── status=     │                     │
  │                     │ charge      │   COMPLETED ─►│                     │
  │                     │ + addBal()  │── balance+=  ►│                     │
  │                     │ + ledger    │── INSERT ────►│                     │
  │                     └──────┬──────┘               │                     │
  │                            │                      │                     │
  │◄── 200 { balance } ───────│                      │                     │
```

### 2. 기프티콘 구매

```
Client ── POST /gifticons ──► GifticonService
                                    │
                              ┌─────┴─────┐ Single TX
                              │ 1. 상품 검증│ (exists? active?)
                              │ 2. 잔액 차감│ UPDATE wallet SET balance = balance - price
                              │            │ WHERE balance >= price  ← 원자적 보호
                              │ 3. 기프티콘 │ INSERT gifticon (ISSUED)
                              │    발급    │
                              │ 4. 원장 기록│ INSERT ledger (PURCHASE, -price)
                              └─────┬─────┘
                                    │
Client ◄── 201 { code, balance } ───┘
```

### 3. 기프티콘 소비

```
Client ── POST .../consume ──► GifticonService
                                    │
                              ┌─────┴─────┐
                              │ UPDATE     │ WHERE status = 'ISSUED'
                              │ gifticon   │ → status = 'CONSUMED'
                              │            │ → consumedAt = now
                              └─────┬─────┘
                                    │  (0 rows updated → AlreadyConsumedException)
Client ◄── 200 { status } ─────────┘
```

---

## 도메인 모델 (ER 다이어그램)

```
┌──────────┐        ┌──────────┐
│  User    │ 1───1  │  Wallet  │
│──────────│        │──────────│
│ id (PK)  │        │userId(PK)│
│ name     │        │ balance  │
│ createdAt│        │ version  │
└────┬─────┘        └──────────┘
     │
     │ 1:N
     ├──────────────────────────────────┐
     │                                  │
┌────▼─────────┐               ┌───────▼──────────┐
│   Charge     │               │    Gifticon      │
│──────────────│               │──────────────────│
│ id (PK)      │               │ id (PK)          │
│ userId (FK)  │               │ userId (FK)      │
│ idempotencyKey│              │ productId (FK) ──┼──► GifticonProduct
│ amount       │               │ code (UQ)        │    │ id (PK)
│ status       │               │ status           │    │ name
│  (REQUESTED/ │               │  (ISSUED/        │    │ price
│   PG_APPROVED│               │   CONSUMED/      │    │ isActive
│   COMPLETED/ │               │   CANCELED)      │
│   FAILED)    │               │ issuedAt         │
│ pgTxId       │               │ consumedAt       │
│ UQ(userId,   │               └──────────────────┘
│  idempKey)   │
└──────┬───────┘
       │
       │ refId
       ▼
┌──────────────┐
│ PointLedger  │ ◄── Gifticon 도 refId 로 참조
│──────────────│
│ id (PK)      │
│ userId (FK)  │
│ type         │
│  (CHARGE/    │
│   PURCHASE/  │
│   REFUND)    │
│ refId        │
│ amountDelta  │
│ createdAt    │
└──────────────┘
```

---

## 동시성 & 일관성 전략

| 전략 | 구현 | 방어 대상 |
|------|------|----------|
| **충전 멱등성** | UQ(user_id, idempotency_key) + 3-Phase 패턴 | 클라이언트 재시도 중복 충전 |
| **잔액 원자성** | SQL `UPDATE WHERE balance >= amount` | 음수 잔액, 레이스 컨디션 |
| **기프티콘 소비 보호** | SQL `UPDATE WHERE status='ISSUED'` | 이중 소비 |
| **낙관적 락** | Wallet `@Version` | 동시 잔액 수정 |
| **감사 추적** | PointLedger (불변 이벤트 기록) | 거래 누락, 잔액 불일치 |

---

## 인프라 & 배포 구조 (Docker Compose)

```
┌─────────────────────────────────────────────────────────────┐
│                    Docker Compose Stack                       │
│                                                               │
│  ┌─────────────┐      ┌──────────────┐    ┌──────────────┐  │
│  │     db       │      │     api      │    │   report     │  │
│  │ PostgreSQL 16│◄─────│ Spring Boot  │    │   Nginx      │  │
│  │ :19432→5432  │ JDBC │ :19080→8080  │    │ :19000→80    │  │
│  └─────────────┘      └──────┬───────┘    └──────┬───────┘  │
│         ▲                    ▲                    │          │
│         │              ┌─────┴──────┐      HTML Reports     │
│  ┌──────┴──────┐       │   seeder   │      from k6          │
│  │ seed.sh     │       │ curl seed  │            │          │
│  │ snapshot.sh │       │ 30K users  │            │          │
│  └─────────────┘       │ 3 products │            │          │
│                        └────────────┘            │          │
│                                                   │          │
│  ┌─────────────────── k6 Profiles ───────────────┘──────┐   │
│  │                                                        │   │
│  │  [load]            [idempotency]     [balance-race]   │   │
│  │  k6 container      k6-idem           k6-balance-race  │   │
│  │  - 충전→구매→소비   - 동일키 동시충전   - 동시 구매 경쟁  │   │
│  │  - VU ramp-up      - 200+409 패턴     - 음수잔액 방지   │   │
│  │  - p95 < 2s        - 잔액 정합성       - 정확히 1건 성공 │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                               │
│  ./run-test.sh <scenario> [VUS] [ITER]                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 테스트 피라미드

```
                    ┌───────────────┐
                    │   k6 부하 테스트 │  Docker Compose 전체 스택
                    │  (3 시나리오)    │  load / idempotency / balance-race
                    └───────┬───────┘
                   ┌────────┴────────┐
                   │ 동시성 테스트     │  Testcontainers + PostgreSQL
                   │ @Tag("docker")  │  ChargeConcurrency, Purchase, Consume
                   └────────┬────────┘
              ┌─────────────┴─────────────┐
              │    통합 테스트 (H2)         │  Repository + Controller (MockMvc)
              └─────────────┬─────────────┘
         ┌──────────────────┴──────────────────┐
         │         단위 테스트 (Mockito)          │  Service 로직 검증
         └─────────────────────────────────────┘
```

**실행 명령어:**

```bash
./gradlew test              # 단위 + 통합 (H2)
./gradlew testWithDocker    # 동시성 (Testcontainers)
./run-test.sh load          # k6 부하 테스트
./run-test.sh idempotency   # k6 멱등성 테스트
./run-test.sh balance-race  # k6 잔액 경쟁 테스트
```

---

## REST API 요약

| Method | Endpoint | 설명 | 비고 |
|--------|----------|------|------|
| `POST` | `/api/users` | 사용자 생성 | Wallet 동시 생성 |
| `GET` | `/api/users/{userId}/wallet` | 잔액 조회 | |
| `POST` | `/api/wallets/{userId}/charges` | 포인트 충전 | `Idempotency-Key` 헤더 필수 |
| `POST` | `/api/products` | 상품 등록 | |
| `GET` | `/api/products` | 활성 상품 목록 | isActive=true 만 |
| `POST` | `/api/users/{userId}/gifticons` | 기프티콘 구매 | 잔액 차감 |
| `POST` | `/api/users/{userId}/gifticons/{id}/consume` | 기프티콘 소비 | 1회만 가능 |

---

## 기술 스택

| 레이어 | 기술 |
|--------|------|
| Language | Kotlin 1.9.25 |
| Framework | Spring Boot 3.3.5 + Spring Data JPA |
| Database | PostgreSQL 16 (prod) / H2 (test) |
| Build | Gradle Kotlin DSL |
| Container | Docker + Docker Compose (multi-profile) |
| Load Test | k6 (grafana/k6) + Nginx (report) |
| Concurrency Test | Testcontainers + JUnit 5 |
| PG Mock | 자체 구현 (delay/fail-rate 설정 가능) |
