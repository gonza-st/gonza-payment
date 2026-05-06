# EDA 버전: 6개 시나리오가 어떻게 풀리는가

> 본 문서는 [README.md](./README.md)의 6개 시나리오를 EDA(Spring `ApplicationEventPublisher` + `@TransactionalEventListener` + `@Async`) 로 재구성했을 때의 변화를 정리한다.
>
> 인프라 추가 없이(메시지 브로커 없음) 같은 프로세스 안에서 책임 분리만으로 어디까지 풀리는지 — 그리고 무엇이 남는지 — 가 핵심이다.

---

## 구조 변화

### Before — 모든 책임이 ChargeFacade 에

```
ChargeFacade (의존성 7개)
├── 라우팅 규칙 (시나리오3, 4)
├── 채널별 재시도 정책 (시나리오5)
├── 알림 발송 (시나리오1, 2)
├── 리워드 지급 (시나리오6)
├── 데이터레이크 적재 (시나리오6)
└── FDS 사전 검사 (시나리오6)
```

### After — 이벤트로 분리

```
ChargeFacade (의존성 3개)
└── publishEvent(ChargeCompletedEvent)

NotificationRouter        # 라우팅 규칙만
ChannelDispatcher         # 채널별 SLA/재시도만
ChargeNotificationListener  ─┐
ChargeRewardListener         ├── @Async @TransactionalEventListener(AFTER_COMMIT)
ChargeDataLakeListener      ─┘
FdsClient (ChargeFacade 안에 그대로)  # 결제 차단을 위한 동기 사전 검사
```

---

## 시나리오별 해결 정도

| 시나리오 | 해결 정도 | 어떻게 풀렸나 |
|---|---|---|
| 1. 응답 시간 누적 | ✅ 해결 | 알림/리워드/데이터레이크가 `@Async` 비동기 → 충전 API 응답엔 포함 안 됨 |
| 2. 한 채널 지연이 결제 죽임 | ✅ 해결 | 채널 호출이 별도 스레드풀(`eda-*`) → Tomcat 스레드 미점유 |
| 3. 라우팅 오염 | ✅ 해결 | `NotificationRouter` 단일 책임 빈 |
| 4. 테스트 폭발 | ✅ 해결 | 라우팅 16 케이스는 `NotificationRouterTest` 로, 충전 본연은 `ChargeFacadeTest` 로 분리 |
| 5. 채널별 SLA/재시도 | ✅ 해결 | `ChannelDispatcher` 단일 책임 빈, 정책 추가/변경이 ChargeFacade 와 무관 |
| 6. 배포 병목 | 🟡 부분 해결 | 코드 분리는 됐으나 in-process 라 같은 빌드/배포 단위. 진짜 격리는 Outbox + 메시지 브로커 + 별도 서비스 |

---

## 남은 한계 (in-process EDA 의 경계)

1. **ChargeFacade ↔ 리스너가 같은 빌드 단위**
   - 같은 jar 에 있으므로 한 팀이 다른 팀 코드를 깨뜨리는 건 여전히 가능
   - 진짜 격리는 별도 모듈/서비스로 분리해야 함

2. **이벤트 발행 ↔ 핸들러 사이 신뢰성**
   - `AFTER_COMMIT` 이지만 핸들러 실행이 실패하면 재시도 없음 (in-memory 이벤트라 손실 가능)
   - 트랜잭션과 외부 호출의 정합성을 강하게 보장하려면 **Outbox 패턴** 필요

3. **부하 격리**
   - 같은 JVM 의 `eda-*` 스레드풀이 포화되면 JVM 전체 영향
   - Kafka/RabbitMQ 같은 외부 브로커가 있으면 컨슈머 그룹별 격리 가능

4. **재처리/재생**
   - 이벤트가 메모리에서 사라지면 다시 못 부음
   - 데이터레이크 적재 실패 시 과거 이벤트를 다시 흘려보내려면 영속화 필요

5. **순서/중복 보장**
   - in-process publish 는 순서는 보장되나, 다중 인스턴스에선 보장 안 됨
   - `MARKETING_HUB` 같은 채널은 외부 브로커의 partition key 가 필요

6. **FDS 는 EDA 로 안 풀린다**
   - "거부 시 결제 차단" 이라는 요구는 본질적으로 동기
   - EDA 가 만능이 아니라는 걸 보여주는 사례 — `ChargeFacade` 안에 그대로 둠

---

## 다음 단계 후보

| 단계 | 도구 | 추가로 풀리는 것 |
|---|---|---|
| 1 (지금) | Spring Events + `@Async` | 시나리오 1~5 + 6 코드 분리 |
| 2 | Outbox 테이블 + 스케줄러 폴링 | 이벤트 손실 방지, 재처리 가능 |
| 3 | Outbox + Kafka/RabbitMQ | 부하 격리, 컨슈머 그룹별 확장 |
| 4 | 별도 서비스로 분리 | 시나리오 6 완전 해결 (배포 격리) |

---

## 핵심 한 줄

> **EDA 자체보다 "이벤트 기반으로 사고하는 코드 구조" 가 시나리오 6개 중 5개를 푼다. 메시지 브로커는 그 다음 단계의 도구다.**
