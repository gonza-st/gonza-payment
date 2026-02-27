# /charge

## Goal

중복 충전(멱등성) + PG 승인 지연

### 상황

- 클라이언트가 /charges 요청 후 타임아웃
- 재시도로 같은 요청이 3번 들어옴
- PG Mock은 첫 요청은 “승인됐지만 응답이 늦게” 옴 (timeout 유발)

### 토론 포인트

- 멱등 키(요청 단위) 설계
- Charge 상태 머신(REQUESTED/APPROVED/COMPLETED/FAILED)
- “승인됐는데 우리 시스템은 실패로 본” 케이스 복구
- at-least-once 환경에서 중복 반영 방지


### 구현 검증(테스트)

- 같은 Idempotency-Key로 3번 호출해도 잔액 1번만 증가
- “승인 지연” 상황에서 재호출 시 동일 결과 반환
- :point_right: 첫 주제로 가장 추천. (결제/분산의 본질)

## Architecture


