package com.gonza.payment.reward

import com.gonza.payment.event.ChargeCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ChargeRewardListener(
    private val rewardService: RewardService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: ChargeCompletedEvent) {
        if (!rewardService.isFirstCharge(event.userId)) return
        runCatching { rewardService.grantInitialCoupon(event.userId) }
            .onFailure { ex -> log.warn("[EDA] 리워드 지급 실패 userId=${event.userId} reason=${ex.message}") }
    }
}
