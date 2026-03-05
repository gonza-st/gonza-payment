# 잔액 경합 시나리오 테스트 (Balance Race Condition Test)

잔액이 1건만 감당 가능한 상태에서 동시 구매 요청 시 잔액 음수 방지 여부를 검증한다.

## 실행

```bash
docker compose --profile balance-race up k6-balance-race

# 파라미터 조절
TARGET_VUS=50 ITERATIONS=20 docker compose --profile balance-race up k6-balance-race
```

## 테스트 흐름

VU마다 아래 순서를 `ITERATIONS`회 반복한다.

```
1. GET  /users/{userId}/wallet            현재 잔액 조회
2. POST /wallets/{userId}/charges          잔액을 정확히 4,500원으로 맞추기
3. POST /users/{userId}/gifticons    ×2   동일 상품 2건 동시 구매 (http.batch)
   ├─ 요청 A ─→ subtractBalance WHERE balance >= 4500
   └─ 요청 B ─→ subtractBalance WHERE balance >= 4500 (동시 도달)
4. sleep 0.5s                              서버 처리 대기
5. GET  /users/{userId}/wallet            구매 후 잔액 조회
6. 잔액 검증                              잔액 >= 0 확인, 음수면 경합 방어 실패
```

### 핵심 메커니즘

`http.batch()`로 **동일 상품 구매 요청 2건**을 동시 전송한다.
잔액이 정확히 상품 가격(4,500원)이므로 **1건만 성공**해야 한다.
두 요청이 동시에 `balance >= price` 조건을 통과하면 잔액이 음수가 된다.

## 기본 설정

| 파라미터 | 기본값 | 환경변수 | 설명 |
|---------|--------|---------|------|
| VU 수 | 20 | `TARGET_VUS` | 동시 테스트 사용자 수 |
| VU당 반복 | 10 | `ITERATIONS` | 사용자별 구매 시도 횟수 |
| 동시 구매 | 2 | `CONCURRENT_REQUESTS` | 동시에 보내는 구매 요청 수 |
| 상품 | 스타벅스 아메리카노 | - | 4,500원 (productIds[0]) |

기본값 기준 총 테스트: **20명 × 10회 = 200건** (각 2건씩 동시 구매 → 총 HTTP 400건+)

## 응답 패턴 분류

동시 2건 구매 요청에 대한 서버 응답 조합을 분류한다.

| 패턴 | 의미 | 판정 |
|------|------|------|
| `201 + 400` | 1건 성공 + 잔액부족 거절 | 이상적 (경합 방어 성공) |
| `201 + 201` | 2건 모두 성공 | 경합실패 (잔액 음수 발생) |
| `201 + 5xx` | 1건 성공 + 서버 에러 | 에러 |
| 전부 4xx | 모두 잔액부족 | 확인 필요 |

## 성공 기준 (Thresholds)

| 지표 | 기준 | 설명 |
|------|------|------|
| `negative_balance` | count == 0 | 잔액 음수 발생 0건 |
| `over_purchase` | count == 0 | 초과 구매 발생 0건 |
| `balance_correct_rate` | rate > 0.95 | 잔액 정합성 95% 이상 |

## 실행 순서

Docker Compose가 아래 순서를 자동 관리한다.

```
db → api → seeder → balance-snapshot → k6-balance-race
                          │
                          └─ 테스트 대상 사용자의 초기 잔액을 JSON으로 저장
                             (HTML 리포트의 사용자별 검증 테이블에서 사용)
```

## 결과 확인

- 터미널: k6 summary + 사용자별 잔액 검증 테이블 (teardown)
- 브라우저: http://localhost:19000/balance-race-report.html
  - 사용자별 잔액 검증 섹션은 API 서버 구동 중일 때 실시간 조회
