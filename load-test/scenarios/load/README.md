# 부하 테스트 (Load Test)

포인트 충전 → 기프티콘 구매 → 사용 흐름을 다수의 동시 사용자가 반복 실행하여
서버의 처리량과 응답 시간을 측정한다.

## 실행

```bash
docker compose up k6                    # 기본 100 VU
TARGET_VUS=500 docker compose up k6     # VU 수 조절
```

## 테스트 흐름

VU마다 아래 순서를 반복한다 (VU간 병렬, VU 내부 순차).

```
1. 랜덤 사용자 선택 (30,000명 중)
2. POST /wallets/{userId}/charges        충전 10,000원 (Idempotency-Key: UUID)
3. POST /users/{userId}/gifticons        랜덤 상품으로 기프티콘 구매
4. POST /users/{userId}/gifticons/{id}/consume   기프티콘 사용
5. sleep 0.5s
```

## 부하 프로필

`TARGET_VUS`에 비례하여 자동 조정된다 (기본값 100 기준).

| 구간 | 시간 | VU 수 | 설명 |
|------|------|-------|------|
| Ramp-up | 30s | 0 → 50 | 점진적 증가 |
| Sustained | 2m | 50 유지 | 안정 부하 |
| Spike | 30s | 50 → 100 | 순간 급증 |
| Sustained High | 1m | 100 유지 | 고부하 유지 |
| Ramp-down | 30s | 100 → 0 | 점진적 감소 |

총 소요시간: **약 4분 30초**

## 성공 기준 (Thresholds)

| 지표 | 기준 |
|------|------|
| HTTP 실패율 | < 10% |
| 응답시간 p(95) | < 2,000ms |
| 응답시간 p(99) | < 5,000ms |

## 사전 데이터

`seed.sh`가 자동 실행되어 아래 데이터를 생성한다.

- 사용자 30,000명
- 상품 3개 (스타벅스 아메리카노 4,500원, 배스킨라빈스 싱글 5,500원, CU 편의점 2,000원)

## 결과 확인

- 터미널: k6 기본 summary 출력
- 브라우저: http://localhost:19000/report.html
