package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ChargeFacade(
    private val chargeService: ChargeService,
    private val notificationService: NotificationService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
        val response = chargeService.chargePoints(userId, amount, idempotencyKey)

        if (response.status == ChargeStatus.COMPLETED) {
            runCatching {
                notificationService.notify(
                    userId = userId,
                    title = "포인트 충전 완료",
                    content = "${amount}P가 충전되었습니다. 현재 잔액 ${response.balance}P"
                )
            }.onFailure { ex ->
                log.warn("SMS 알림 실패 (chargeId=${response.chargeId}): ${ex.message}")
            }
        }
        return response
    }
}
