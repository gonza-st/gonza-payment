package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.User
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.UserRepository
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
    @Value("\${notification.channel.timeout-ms:0}") private val channelTimeoutMs: Long,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun chargePoints(
        userId: UUID,
        amount: Long,
        idempotencyKey: String
    ): ChargeResponse {
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
        return response
    }

    private fun notify(userId: UUID, title: String, content: String, channel: NotificationChannel, chargeId: UUID) {
        val start = System.currentTimeMillis()

        if (channelTimeoutMs > 0) {
            // 타임아웃 설정 시: ForkJoin 스레드로 오프로드 후 제한 시간 대기
            // ※ Tomcat 스레드는 여전히 channelTimeoutMs 동안 블로킹됨 (band-aid fix)
            val future = CompletableFuture.supplyAsync {
                notificationService.notify(userId, title, content, channel)
            }
            runCatching {
                future.get(channelTimeoutMs, TimeUnit.MILLISECONDS)
            }.onFailure { ex ->
                future.cancel(true)
                val elapsed = System.currentTimeMillis() - start
                val reason = if (ex is TimeoutException) "TIMEOUT(${elapsed}ms > ${channelTimeoutMs}ms)" else ex.message
                log.warn("[시나리오2] $channel 실패 chargeId=$chargeId reason=$reason")
            }
        } else {
            // 타임아웃 없음: runCatching은 예외만 잡고 지연은 그대로 전파됨
            runCatching {
                notificationService.notify(userId, title, content, channel)
            }.onFailure { ex ->
                log.warn("[시나리오2] $channel 실패 chargeId=$chargeId reason=${ex.message}")
            }
        }

        val elapsed = System.currentTimeMillis() - start
        log.info("[시나리오2] $channel elapsed=${elapsed}ms chargeId=$chargeId")
    }

    private fun User.isForeignNumber(): Boolean {
        val digits = phoneNumber.filter { it.isDigit() }
        return !digits.startsWith("010") && !digits.startsWith("8210")
    }

    companion object {
        private val NIGHT_START: LocalTime = LocalTime.of(22, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(8, 0)
    }
}
