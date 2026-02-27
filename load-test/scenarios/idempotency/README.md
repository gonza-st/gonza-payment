# 중복결제 시나리오 테스트 (Idempotency Test)

동일 `Idempotency-Key`로 동시에 충전 요청을 보내 중복결제가 발생하는지 검증한다.
PG 응답 지연 중 클라이언트가 재요청하는 상황을 시뮬레이션한다.

## 실행

```bash
PG_MOCK_DELAY_MS=2000 docker compose up k6-idem

# 파라미터 조절
TARGET_VUS=50 ITERATIONS=20 PG_MOCK_DELAY_MS=2000 docker compose up k6-idem
```

## 테스트 흐름

VU마다 아래 순서를 `ITERATIONS`회 반복한다.

```
1. GET  /users/{userId}/wallet            충전 전 잔액 조회
2. POST /wallets/{userId}/charges  ×2     동일 Idempotency-Key로 2건 동시 요청 (http.batch)
   ├─ 요청 A ─→ 서버 처리 중 (PG 지연 2초)
   └─ 요청 B ─→ 동시에 도달 (같은 키)
3. sleep 1s                               서버 처리 대기
4. GET  /users/{userId}/wallet            충전 후 잔액 조회
5. 잔액 차이 검증                          기대값: +10,000원 정확히 1건
```

### 핵심 메커니즘

`http.batch()`로 **동일 Idempotency-Key**의 요청 2건을 동시 전송한다.
`PG_MOCK_DELAY_MS=2000`으로 PG 응답을 2초 지연시켜,
첫 번째 요청이 처리되는 동안 두 번째 요청이 서버에 도달하도록 한다.

## 기본 설정

| 파라미터 | 기본값 | 환경변수 | 설명 |
|---------|--------|---------|------|
| VU 수 | 20 | `TARGET_VUS` | 동시 테스트 사용자 수 |
| VU당 반복 | 10 | `ITERATIONS` | 사용자별 충전 시도 횟수 |
| 동시 요청 | 2 | `CONCURRENT_REQUESTS` | 같은 키로 보내는 동시 요청 수 |
| PG 지연 | 0ms | `PG_MOCK_DELAY_MS` | PG 모의 응답 지연 (2000 권장) |

기본값 기준 총 테스트: **20명 × 10회 = 200건** (각 2건씩 동시 요청 → 총 HTTP 400건+)

## 응답 패턴 분류

동시 2건 요청에 대한 서버 응답 조합을 분류한다.

| 패턴 | 의미 | 판정 |
|------|------|------|
| `200 + 409` | 1건 성공 + 충돌 반환 | 이상적 (앱 레벨 방어) |
| `200 + 200` | 2건 모두 200 | 확인 필요 (멱등 반환 or 중복) |
| `200 + 5xx` | 1건 성공 + 미처리 예외 | 버그 (DataIntegrityViolation) |
| 전부 실패 | 200 응답 없음 | 장애 |

## 성공 기준 (Thresholds)

| 지표 | 기준 | 설명 |
|------|------|------|
| `duplicate_charges` | count == 0 | 잔액 기준 중복 충전 0건 |
| `balance_correct_rate` | rate > 0.95 | 충전 전후 잔액이 정확한 비율 95% 이상 |

## 실행 순서

Docker Compose가 아래 순서를 자동 관리한다.

```
db → api → seeder → idem-snapshot → k6-idem
                         │
                         └─ 테스트 대상 사용자의 초기 잔액을 JSON으로 저장
                            (HTML 리포트의 사용자별 검증 테이블에서 사용)
```

## 결과 확인

- 터미널: k6 summary + 사용자별 잔액 검증 테이블 (teardown)
- 브라우저: http://localhost:19000/idempotency-report.html
  - 사용자별 잔액 검증 섹션은 API 서버 구동 중일 때 실시간 조회
