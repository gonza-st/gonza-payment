# 스터디 프로젝트 로드맵

## 핵심 원칙: "왜 필요한지 먼저 고통받고, 그 다음에 도입"

프로덕션에서는 "필요할 때 도입"이지만, **스터디에서는 인위적으로 문제를 만들고 해결해보는 것**이 목적이다.

---

## Phase 1 — 현재 모놀리스 완성도 높이기

지금 코드에서 설계 결정을 명확히 해두기:
- 빨간 스티키 (`구매요청 vs 별도 테이블`) 해결 → `PurchaseRequest` 도메인 추가
- 패키지를 도메인별로 명확하게 경계 강화
- 현재 동시성 처리 방식의 장단점을 문서화

**목표**: "내가 왜 이렇게 설계했는가"를 설명할 수 있는 상태

---

## Phase 2 — 의도적으로 문제 만들기

현재 모놀리스의 한계를 직접 체험하는 시나리오를 만들어본다:

```
시나리오: "충전 완료 후 알림 발송을 추가해주세요"
→ ChargeService에 NotificationService 직접 의존 추가
→ "이제 SMS도 보내주세요, 이메일도요, 마케팅 시스템에도 전달해주세요"
→ 의존이 폭발하는 걸 직접 느낀다
```

이 고통을 느낀 후에 이벤트를 도입하면 **"아 이래서 쓰는구나"** 가 체득된다.

---

## Phase 3 — 단계적으로 EDA 도입 실험

한 번에 다 바꾸지 말고, **하나씩 이유를 가지고** 도입한다.

### Step 3-1. 도메인 이벤트 (코드 레벨)

Spring `ApplicationEvent`로 내부 이벤트 발행. 인프라 없이 이벤트 사고방식 먼저 익히기.

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

### Step 3-2. Outbox 패턴

이벤트를 DB에 먼저 저장하고 발행하는 패턴. **왜 필요한지가 제일 중요한 학습이다.**

```
TX 안에서:
  charge.status = COMPLETED
  outbox.save(ChargeCompletedEvent)   ← 같은 TX에 저장
  
TX 이후:
  Outbox Poller → Message Broker 발행
```

이게 없으면 "DB 반영됐는데 이벤트가 안 날아감" 또는 "이벤트는 날아갔는데 DB 롤백됨" 상황이 생긴다.

### Step 3-3. 실제 브로커

Kafka나 RabbitMQ 붙이기. Outbox 고통을 겪은 후라 이해도가 다르다.

---

## Phase 4 (선택) — MSA 분리 실험

하나의 도메인만 분리해본다:

```
충전 서비스만 별도 서비스로 분리
→ 그러면 Wallet은? Ledger는?
→ 분산 트랜잭션 문제 직접 맞닥뜨리기
→ Saga 패턴 or 2PC 실험
```

---

## 단계별 요약

| 단계 | 키워드 | 배우는 것 |
|------|--------|----------|
| Phase 1 | 설계 결정 문서화 | 왜 이 구조인가 |
| Phase 2 | 의도적 고통 | 모놀리스 한계 체험 |
| Phase 3 | 점진적 EDA | 이벤트가 해결하는 문제 |
| Phase 4 | MSA 맛보기 | 분산 시스템의 복잡도 |

---

## 기록 방식 제안

각 단계에서 **"이전보다 무엇이 나아졌고, 무엇이 더 복잡해졌는가"** 를 직접 비교할 수 있는 상태로 남긴다.

브랜치나 README로 before/after를 명확히 남기면 포트폴리오로도 활용할 수 있다.
