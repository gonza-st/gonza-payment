# 시나리오: 팬아웃 폭발 (Fan-out Explosion)

> "채널 추가는 `forEach`에 한 줄 더일 뿐"이라는 환상이 깨지는 지점들.
>
> 이 문서는 해결방안을 제시하지 않는다. **"이런 상황에 놓이면 코드/운영이 이렇게 될 수 있다"** 를 예측 가능한 형태로 기록해 두는 것이 목적이다.

---

## 배경 — 지금의 평화

현재 `ChargeFacade.chargePoints()`는 충전 완료 후 2개 채널(SMS, EMAIL)로 알림을 동기 직렬 발송한다.

```kotlin
// src/main/kotlin/com/gonza/payment/facade/ChargeFacade.kt (현재)
@Component
class ChargeFacade(
    private val chargeService: ChargeService,
    private val notificationService: NotificationService
) {
    fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
        val response = chargeService.chargePoints(userId, amount, idempotencyKey)

        if (response.status == ChargeStatus.COMPLETED) {
            listOf(NotificationChannel.SMS, NotificationChannel.EMAIL).forEach { channel ->
                runCatching {
                    notificationService.notify(userId, title, content, channel)
                }.onFailure { ex -> log.warn("$channel 알림 실패: ${ex.message}") }
            }
        }
        return response
    }
}
```

채널이 2개일 땐 평화롭다. 코드도 짧고, 테스트도 "호출됐는가" 2줄이면 끝난다.

아래는 이 평화가 어떻게 깨질 수 있는지에 대한 기록이다.

---

## 시나리오 1. 응답 시간이 채널 수에 선형으로 커진다

동기 직렬 호출이므로 전체 API 응답 시간 = Σ(각 채널 latency).

**현재 (2 채널)**

```
ChargeService(50ms) → SMS(200ms) → Email(300ms)
= 550ms
```

**6개월 후, 채널 6개가 되었다고 가정**

```
ChargeService(50ms)
  → SMS(200ms)
  → Email(300ms)
  → Push/FCM(150ms)
  → 카카오 알림톡(400ms)     ← 외부 승인 대기
  → Slack 내부 모니터링(250ms)
  → 마케팅 이벤트 허브(100ms)
= 1,450ms
```

**관찰되는 증상**

- Charge 본연의 처리는 그대로 50ms.
- 사용자는 1.5초를 기다린다.
- 기존 SLA가 p95 < 1s 였다면 **이 시점에 계약 위반**.
- 신규 채널 한 개 붙일 때마다 대시보드의 응답 시간 그래프가 계단식으로 올라간다.

---

## 시나리오 2. 채널 하나의 지연이 충전 전체를 죽인다

어느 날, 카카오 알림톡 서버가 3초 지연되기 시작한다.

```kotlin
runCatching {
    kakaoAlimtalkSender.send(...)   // 기본 타임아웃 5초
}.onFailure { log.warn(...) }
```

`runCatching`은 **예외만 잡는다. 지연은 못 잡는다.**

**관찰되는 증상**

- Tomcat 스레드 200개가 전부 카카오 응답 대기에 묶인다.
- 신규 충전 요청은 큐에 적체 → 클라이언트 타임아웃.
- CS 티켓: **"충전이 안 돼요"** — 실제 원인은 **알림 서버**.
- Charge 서비스 대시보드 p95: 550ms → 5,000ms.
- 근본 원인(알림 채널) 찾기까지 평균 30분~1시간, 그동안 결제 포기율 상승.

알림이 실패한 게 아니라 **결제 자체가 실패한 것처럼 보이는** 상황이다.

---

## 시나리오 3. 라우팅 규칙이 Facade를 뒤덮는다

아래 요구사항들이 한 분기 안에 순차적으로 들어왔다고 가정한다.

| 시점 | 요구사항 |
|---|---|
| 2026-05 | 야간(22~08시)엔 SMS 대신 Push |
| 2026-06 | VIP 회원에게만 알림톡 추가 발송 |
| 2026-07 | 마케팅 수신 동의자에게 Slack 이벤트 쿠폰 알림 |
| 2026-08 | 외국 번호 사용자에게는 SMS 제외, Email만 |

**4개월 뒤의 `chargePoints()` 내부**

```kotlin
fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
    val response = chargeService.chargePoints(userId, amount, idempotencyKey)
    if (response.status != ChargeStatus.COMPLETED) return response

    val user = userRepository.findById(userId).orElseThrow()
    val channels = mutableListOf<NotificationChannel>()

    if (!user.isForeignNumber()) {
        channels += if (LocalTime.now().isNight()) PUSH else SMS
    }
    channels += EMAIL
    if (user.isVip()) channels += KAKAO_ALIMTALK
    if (user.marketingOptIn) channels += SLACK_MARKETING

    channels.forEach { channel ->
        runCatching { notificationService.notify(userId, title, content, channel) }
            .onFailure { log.warn("$channel 알림 실패: ${it.message}") }
    }
    return response
}
```

**이 함수가 담고 있는 관심사의 비율**

- 충전: 1줄 (`chargeService.chargePoints(...)`)
- 알림 라우팅: 12줄

"충전 Facade"가 **알림 라우팅 규칙의 쓰레기통**으로 변한다. 이후 "주말엔 알림톡 제외", "B2B 계정은 SMS만", "앱 미설치자에게 Push 제외" 같은 요구사항이 추가될 때마다 이 함수에 분기가 한 층씩 쌓인다.

---

## 시나리오 4. 테스트가 충전이 아니라 라우팅을 검증한다

**현재의 `ChargeFacadeTest`**

```kotlin
@Test fun `충전 완료 시 SMS 와 EMAIL 알림을 호출한다`() { ... }
@Test fun `충전 실패 시 알림을 호출하지 않는다`() { ... }
```

**시나리오 3 이후 예상 테스트**

```kotlin
@ParameterizedTest
@CsvSource(
    // phone, night, vip, marketingOptIn, expectedChannels
    "NORMAL,  FALSE, FALSE, FALSE, 'SMS,EMAIL'",
    "NORMAL,  TRUE,  FALSE, FALSE, 'PUSH,EMAIL'",              // 야간
    "NORMAL,  FALSE, TRUE,  FALSE, 'SMS,EMAIL,KAKAO'",          // VIP
    "NORMAL,  FALSE, FALSE, TRUE,  'SMS,EMAIL,SLACK'",          // 마케팅
    "NORMAL,  TRUE,  TRUE,  TRUE,  'PUSH,EMAIL,KAKAO,SLACK'",
    "FOREIGN, FALSE, FALSE, FALSE, 'EMAIL'",
    "FOREIGN, TRUE,  TRUE,  TRUE,  'EMAIL,KAKAO,SLACK'",
    // ... 조합이 2 × 2 × 2 × 2 = 16 케이스
)
fun `충전 완료 시 사용자 상태 X 시간대 조합에 맞는 채널로만 발송한다`(...) { ... }
```

**관찰되는 증상**

- 테스트 케이스가 채널/조건 수의 조합으로 폭발.
- 테스트 실패 시 **충전 로직이 깨진 건지, 라우팅 규칙이 깨진 건지 분간이 어렵다.**
- Mock 주입이 6~8개로 늘어 테스트 셋업이 본체보다 길어진다.
- 알림 정책이 바뀔 때마다 충전 테스트가 깨진다 (두 관심사가 한 테스트에 묶여 있다는 증거).

---

## 시나리오 5. 채널마다 다른 SLA/재시도 정책이 한 곳에 뭉친다

채널별로 현실적인 운영 요구사항은 다르다.

| 채널 | 실패 시 정책 |
|---|---|
| SMS | 즉시 재시도 3회 (일시 실패 흔함) |
| Email | 24시간 내 최대 5회, 지수 백오프 |
| 카카오 알림톡 | 발송 승인 갱신 후 재시도, 템플릿 거부는 재시도 불가 |
| Slack 마케팅 | 실패해도 무시 (베스트 에포트) |
| 마케팅 이벤트 허브 | 순서 보장, 중복 허용 안 됨 |

**동기 팬아웃에서 SMS 재시도를 넣어본다고 하면**

```kotlin
runCatching {
    repeat(3) { attempt ->
        val result = smsSender.send(...)
        if (result.success) return@runCatching
        Thread.sleep(100L * (attempt + 1))   // 100ms, 200ms, 300ms
    }
}
```

**드러나는 딜레마**

- SMS 재시도 최악 시 **+600ms**가 충전 API 응답 시간에 추가된다.
- Email까지 재시도를 추가하면 더 쌓인다.
- 응답 시간을 지키려면 재시도를 포기해야 하고, 재시도하려면 응답 시간을 포기해야 한다.
- 타협의 결과: "그냥 실패하면 버려" → "쿠폰 알림 못 받았어요" CS가 쌓인다.

---

## 시나리오 6. 결제 서비스가 모든 컨슈머의 배포 병목이 된다

시간이 더 지나면 `ChargeFacade`는 타 팀의 요청을 받는 창구가 된다.

| 요청자 | 요청 | 결과 |
|---|---|---|
| 마케팅팀 | Slack 문구 변경 | 결제 팀 PR + 배포 |
| CS팀 | 알림톡 템플릿 수정 | 결제 팀 PR + 배포 |
| 데이터팀 | 충전 이벤트를 데이터레이크에 적재 | `ChargeFacade`에 DataLakeClient 의존 추가 |
| 그로스팀 | 최초 충전 사용자에게 리워드 쿠폰 | `ChargeFacade`에 RewardService 의존 추가 |
| 보안팀 | 일정 금액 이상 충전 시 FDS 호출 | `ChargeFacade`에 FdsClient 의존 추가 |

**예상되는 `ChargeFacade` 생성자**

```kotlin
class ChargeFacade(
    private val chargeService: ChargeService,
    private val notificationService: NotificationService,
    private val pushSender: PushSender,
    private val kakaoAlimtalkSender: KakaoAlimtalkSender,
    private val slackMarketingSender: SlackMarketingSender,
    private val marketingHubClient: MarketingHubClient,
    private val dataLakeClient: DataLakeClient,
    private val rewardService: RewardService,
    private val fdsClient: FdsClient,
    private val userRepository: UserRepository,
    // ...
)
```

**관찰되는 증상**

- 결제팀은 본연의 책임(잔액 정합성, 멱등성, PG 연동)이 아닌 **타 팀의 기능 요청**에 대부분의 시간을 쓴다.
- 타 팀의 기능 배포가 결제 배포 사이클에 묶인다.
- 결제 릴리즈 노트에 결제와 무관한 변경이 섞인다.
- `ChargeFacade`를 수정할 때마다 관련 팀 5곳의 리뷰를 받아야 한다.

---

## 체감 실험용 측정 지점

이 시나리오들을 수치로 직접 보려면 다음을 관찰한다.

| 측정 항목 | 도구 |
|---|---|
| 채널 수 vs 충전 API p95 | k6 load 시나리오 + 채널 추가 반복 |
| 단일 채널 `delay-ms` 급등 시 스레드풀 포화 | Spring Actuator `tomcat.threads.busy`, `http.server.requests` |
| `ChargeFacade` 메서드 라인 수 / 생성자 파라미터 수 추이 | git log + `wc -l` |
| `ChargeFacadeTest` 케이스 수 / 실행 시간 추이 | git log + `./gradlew test --tests ChargeFacadeTest` |
| 채널별 실패율, 누락율 | `notifications` 테이블 `status` 집계 |

---

## 요약

| 시나리오 | 한 줄 |
|---|---|
| 1. 응답 시간 누적 | 채널이 늘면 p95가 선형으로 커진다 |
| 2. 장애 전파 | 한 채널의 지연이 결제 자체의 장애로 보인다 |
| 3. 라우팅 오염 | 충전 Facade가 알림 라우팅 규칙의 쓰레기통이 된다 |
| 4. 테스트 변질 | 충전 테스트가 라우팅 조합 테스트로 변한다 |
| 5. 재시도 딜레마 | 재시도와 응답 시간이 서로 잡아먹는다 |
| 6. 배포 병목 | 결제팀이 타 팀의 배포 창구가 된다 |

이 문서는 여기까지만 기록한다. 해결책은 같은 폴더 하위 또는 Phase 3 실험 기록에서 이어간다.
