package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class ChargeFacade(
    private val chargeService: ChargeService,
    private val notificationService: NotificationService,
    @Value("\${notification.channel.timeout-ms:0}") private val channelTimeoutMs: Long
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun chargePoints(
        userId: UUID,
        amount: Long,
        idempotencyKey: String
    ): ChargeResponse {
        val response = chargeService.chargePoints(userId, amount, idempotencyKey)

        if (response.status == ChargeStatus.COMPLETED) {
            val title = "포인트 충전 완료"
            val content = "${amount}P가 충전되었습니다. 현재 잔액 ${response.balance}P"
            listOf(
                NotificationChannel.SMS,
                NotificationChannel.EMAIL,
                NotificationChannel.PUSH,
                NotificationChannel.KAKAO_ALIMTALK,
                NotificationChannel.SLACK,
                NotificationChannel.MARKETING_HUB
            ).forEach { channel ->
                notify(userId, title, content, channel, response.chargeId)
            }
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
}
