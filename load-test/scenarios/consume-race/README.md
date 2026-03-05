# 기프티콘 동시 Consume 시나리오 테스트 (Consume Race Test)

동일 기프티콘에 대해 동시에 consume 요청을 보내 중복 사용이 발생하는지 검증한다.

## 배경

- 네트워크 재시도로 같은 사용자가 consume을 2번 호출
- 운영 실수로 공유된 기프티콘 코드를 2명이 동시에 사용 시도

## 실행

```bash
./run-test.sh consume-race

# 파라미터 조절
./run-test.sh consume-race 50 20 3    # 50 VU, 20회, 동시 3건
```

## 테스트 흐름

VU마다 아래 순서를 `ITERATIONS`회 반복한다.

```
1. POST /users/{userId}/gifticons            기프티콘 구매 (ISSUED 상태)
2. POST /users/{userId}/gifticons/{id}/consume  xN  동시 consume (http.batch)
   |-- 요청 A --> WHERE status='ISSUED' -> UPDATE 성공 -> 200
   |-- 요청 B --> WHERE status='ISSUED' -> 0 rows -> 409 AlreadyConsumed
3. 응답 패턴 검증: 정확히 1건 200, 나머지 409
```

### 핵심 메커니즘

`consumeById()` 쿼리가 `WHERE status = 'ISSUED'` 조건부 업데이트를 사용한다.
동시에 2건이 도달해도 DB 행 잠금에 의해 1건만 `updatedRows = 1`을 얻고,
나머지는 `updatedRows = 0` -> `AlreadyConsumedException` (409) 반환.

## 기본 설정

| 파라미터 | 기본값 | 환경변수 | 설명 |
|---------|--------|---------|------|
| VU 수 | 20 | `TARGET_VUS` | 동시 테스트 사용자 수 |
| VU당 반복 | 10 | `ITERATIONS` | 사용자별 consume 시도 횟수 |
| 동시 요청 | 2 | `CONCURRENT_REQUESTS` | 같은 기프티콘에 보내는 동시 요청 수 |

기본값 기준 총 테스트: **20명 x 10회 = 200건** (각 2건씩 동시 요청 -> 총 HTTP 400건+)

## 응답 패턴 분류

동시 2건 요청에 대한 서버 응답 조합을 분류한다.

| 패턴 | 의미 | 판정 |
|------|------|------|
| `200 + 409` | 1건 성공 + AlreadyConsumed | 이상적 (조건부 업데이트 정상) |
| `200 + 200` | 2건 이상 성공 | 위험 (중복 사용 발생) |
| `200 + 5xx` | 1건 성공 + 미처리 예외 | 버그 |
| 전부 실패 | 200 응답 없음 | 장애 |

## 성공 기준 (Thresholds)

| 지표 | 기준 | 설명 |
|------|------|------|
| `double_consume` | count == 0 | 중복 사용 0건 |
| `single_success_rate` | rate > 0.95 | 정확히 1건 성공 비율 95% 이상 |

## 토론 포인트

> "consume은 멱등하게 할까, 409로 막을까?"

- **409 반환 (현재):** 클라이언트가 "이미 사용됨"을 명확히 인지. 2명 동시 사용 시 누가 실제 사용했는지 구분 가능.
- **멱등 (200 반환):** 네트워크 재시도에 안전. 하지만 "2명 동시 사용"과 "1명 재시도"를 구분 불가.
- **하이브리드:** 같은 사용자 재시도면 200, 다른 사용자면 409. 가장 세밀하지만 복잡도 증가.
- **감사 로그:** 어떤 정책이든 사용 시도 이력(성공/실패/시각/요청자)을 기록하면 운영 추적 가능.

## 결과 확인

- 터미널: k6 summary
- 브라우저: http://localhost:19000/consume-race/report.html
