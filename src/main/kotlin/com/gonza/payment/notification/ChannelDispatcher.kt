package com.gonza.payment.notification

import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ChannelDispatcher(
    private val notificationService: NotificationService,
    private val backoff: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) }
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun send(userId: UUID, title: String, content: String, channel: NotificationChannel, chargeId: UUID): NotificationStatus? {
        val policy = retryPolicyFor(channel)
        var attempt = 0
        var lastStatus: NotificationStatus? = null

        while (attempt < policy.maxAttempts) {
            attempt++
            lastStatus = runCatching {
                notificationService.notify(userId, title, content, channel)
            }.onFailure { ex ->
                log.warn("[EDA] $channel 호출 예외 chargeId=$chargeId reason=${ex.message}")
            }.getOrNull()?.status

            if (lastStatus == NotificationStatus.SENT) break
            if (attempt < policy.maxAttempts) {
                val sleepMs = policy.backoffMs(attempt)
                log.warn("[EDA] $channel 재시도 attempt=$attempt sleepMs=$sleepMs chargeId=$chargeId")
                backoff(sleepMs)
            }
        }
        log.info("[EDA] $channel attempts=$attempt status=$lastStatus chargeId=$chargeId")
        return lastStatus
    }

    private fun retryPolicyFor(channel: NotificationChannel): RetryPolicy = when (channel) {
        NotificationChannel.SMS -> RetryPolicy(maxAttempts = 3) { attempt -> 100L * attempt }
        NotificationChannel.EMAIL -> RetryPolicy(maxAttempts = 2) { attempt -> 200L * (1L shl (attempt - 1)) }
        else -> RetryPolicy(maxAttempts = 1) { 0L }
    }

    private data class RetryPolicy(val maxAttempts: Int, val backoffMs: (Int) -> Long)
}
