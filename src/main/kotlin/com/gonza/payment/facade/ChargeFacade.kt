package com.gonza.payment.facade

import com.gonza.payment.datalake.ChargeEvent
import com.gonza.payment.datalake.DataLakeClient
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.domain.User
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.fds.FdsClient
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.reward.RewardService
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class ChargeFacade(
    private val chargeService: ChargeService,
    private val notificationService: NotificationService,
    private val userRepository: UserRepository,
    // [시나리오6] 타 팀 요청으로 추가된 의존성들 — ChargeFacade 가 결제 외 책임을 떠안는다
    private val fdsClient: FdsClient,                  // 보안팀: 일정 금액 이상 사전 검사
    private val rewardService: RewardService,          // 그로스팀: 최초 충전 쿠폰 지급
    private val dataLakeClient: DataLakeClient,        // 데이터팀: 충전 이벤트 적재
    @Value("\${notification.channel.timeout-ms:0}") private val channelTimeoutMs: Long,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val backoff: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun chargePoints(
        userId: UUID,
        amount: Long,
        idempotencyKey: String
    ): ChargeResponse {
        // [시나리오6] 보안팀: 일정 금액 이상이면 FDS 사전 검사 (결제 차단)
        if (amount >= FDS_THRESHOLD) {
            val fds = fdsClient.check(userId, amount)
            if (!fds.approved) {
                log.warn("[시나리오6] FDS 거부 userId=$userId amount=$amount reason=${fds.reason}")
                throw IllegalStateException("FDS rejected: ${fds.reason}")
            }
        }

        val response = chargeService.chargePoints(userId, amount, idempotencyKey)
        if (response.status != ChargeStatus.COMPLETED) return response

        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("User not found: $userId") }
        val title = "포인트 충전 완료"
        val content = "${amount}P가 충전되었습니다. 현재 잔액 ${response.balance}P"

        // [시나리오3] 라우팅 규칙이 Facade를 뒤덮는다
        val channels = mutableListOf<NotificationChannel>()
        val now = LocalTime.now(clock)
        val isNight = !now.isBefore(NIGHT_START) || now.isBefore(NIGHT_END)

        // 2026-08: 외국 번호 사용자는 SMS/PUSH 제외, Email만
        if (!user.isForeignNumber()) {
            // 2026-05: 야간(22~08시)엔 SMS 대신 Push
            channels += if (isNight) NotificationChannel.PUSH else NotificationChannel.SMS
        }
        channels += NotificationChannel.EMAIL

        // 2026-06: VIP 회원에게만 알림톡 추가 발송
        if (user.vip) channels += NotificationChannel.KAKAO_ALIMTALK

        // 2026-07: 마케팅 수신 동의자에게 Slack 이벤트 쿠폰 알림
        if (user.marketingOptIn) channels += NotificationChannel.SLACK

        log.info("[시나리오3] 라우팅 결과 userId=$userId vip=${user.vip} marketingOptIn=${user.marketingOptIn} foreign=${user.isForeignNumber()} night=$isNight channels=$channels")

        channels.forEach { channel ->
            notify(userId, title, content, channel, response.chargeId)
        }

        // [시나리오6] 그로스팀: 최초 충전이면 환영 쿠폰 지급
        if (rewardService.isFirstCharge(userId)) {
            runCatching { rewardService.grantInitialCoupon(userId) }
                .onFailure { ex -> log.warn("[시나리오6] 리워드 지급 실패 userId=$userId reason=${ex.message}") }
        }

        // [시나리오6] 데이터팀: 충전 이벤트를 데이터레이크에 적재
        runCatching {
            dataLakeClient.publish(ChargeEvent(response.chargeId, userId, amount, response.balance))
        }.onFailure { ex -> log.warn("[시나리오6] 데이터레이크 적재 실패 chargeId=${response.chargeId} reason=${ex.message}") }

        return response
    }

    private fun notify(userId: UUID, title: String, content: String, channel: NotificationChannel, chargeId: UUID) {
        val start = System.currentTimeMillis()
        val policy = retryPolicyFor(channel)
        var attempt = 0
        var lastStatus: NotificationStatus? = null

        while (attempt < policy.maxAttempts) {
            attempt++
            lastStatus = invokeOnce(userId, title, content, channel, chargeId)
            if (lastStatus == NotificationStatus.SENT) break

            if (attempt < policy.maxAttempts) {
                val sleepMs = policy.backoffMs(attempt)
                log.warn("[시나리오5] $channel 재시도 attempt=$attempt sleepMs=$sleepMs chargeId=$chargeId")
                backoff(sleepMs)
            }
        }

        val elapsed = System.currentTimeMillis() - start
        log.info("[시나리오5] $channel attempts=$attempt status=$lastStatus elapsed=${elapsed}ms chargeId=$chargeId")
    }

    private fun invokeOnce(userId: UUID, title: String, content: String, channel: NotificationChannel, chargeId: UUID): NotificationStatus? {
        if (channelTimeoutMs > 0) {
            // 시나리오2 band-aid: ForkJoin 스레드로 오프로드 후 제한 시간 대기
            val future = CompletableFuture.supplyAsync {
                notificationService.notify(userId, title, content, channel)
            }
            return runCatching {
                future.get(channelTimeoutMs, TimeUnit.MILLISECONDS)
            }.onFailure { ex ->
                future.cancel(true)
                val reason = if (ex is TimeoutException) "TIMEOUT(${channelTimeoutMs}ms)" else ex.message
                log.warn("[시나리오2] $channel 실패 chargeId=$chargeId reason=$reason")
            }.getOrNull()?.status
        }
        return runCatching {
            notificationService.notify(userId, title, content, channel)
        }.onFailure { ex ->
            log.warn("[시나리오2] $channel 실패 chargeId=$chargeId reason=${ex.message}")
        }.getOrNull()?.status
    }

    // [시나리오5] 채널마다 다른 SLA/재시도 정책이 한 곳에 뭉친다
    //   - SMS: 즉시 재시도 3회, 선형 백오프 100/200/300ms (일시 실패 흔함)
    //   - EMAIL: 24시간 내 5회/지수 백오프 → 동기 팬아웃에선 2회/200·400ms로 타협
    //   - 카카오: 템플릿 거부 가능성 → 동기 재시도 안 함 (별도 갱신 절차 필요)
    //   - SLACK: 마케팅 베스트 에포트 → 실패 무시
    //   - PUSH: 베스트 에포트 → 실패 무시
    //   - MARKETING_HUB: 순서/중복 보장 → 별도 처리 필요, 동기 재시도 안 함
    // 결과: 한 채널 정책이 바뀔 때마다 ChargeFacade 가 수정된다.
    private fun retryPolicyFor(channel: NotificationChannel): RetryPolicy = when (channel) {
        NotificationChannel.SMS -> RetryPolicy(maxAttempts = 3) { attempt -> 100L * attempt }
        NotificationChannel.EMAIL -> RetryPolicy(maxAttempts = 2) { attempt -> 200L * (1L shl (attempt - 1)) }
        else -> RetryPolicy(maxAttempts = 1) { 0L }
    }

    private fun User.isForeignNumber(): Boolean {
        val digits = phoneNumber.filter { it.isDigit() }
        return !digits.startsWith("010") && !digits.startsWith("8210")
    }

    private data class RetryPolicy(val maxAttempts: Int, val backoffMs: (Int) -> Long)

    companion object {
        private val NIGHT_START: LocalTime = LocalTime.of(22, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(8, 0)
        private const val FDS_THRESHOLD: Long = 1_000_000L
    }
}
