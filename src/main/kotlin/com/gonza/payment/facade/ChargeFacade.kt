package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.event.ChargeCompletedEvent
import com.gonza.payment.fds.FdsClient
import com.gonza.payment.service.ChargeService
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ChargeFacade(
    private val chargeService: ChargeService,
    private val fdsClient: FdsClient,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun chargePoints(
        userId: UUID,
        amount: Long,
        idempotencyKey: String
    ): ChargeResponse {
        // 보안팀: 결제 차단을 위한 동기 사전 검사 — EDA 로 풀리지 않는 흐름
        if (amount >= FDS_THRESHOLD) {
            val fds = fdsClient.check(userId, amount)
            if (!fds.approved) {
                log.warn("[EDA] FDS 거부 userId=$userId amount=$amount reason=${fds.reason}")
                throw IllegalStateException("FDS rejected: ${fds.reason}")
            }
        }

        val response = chargeService.chargePoints(userId, amount, idempotencyKey)
        if (response.status == ChargeStatus.COMPLETED) {
            eventPublisher.publishEvent(
                ChargeCompletedEvent(
                    chargeId = response.chargeId,
                    userId = userId,
                    amount = amount,
                    balance = response.balance
                )
            )
        }
        return response
    }

    companion object {
        private const val FDS_THRESHOLD: Long = 1_000_000L
    }
}
