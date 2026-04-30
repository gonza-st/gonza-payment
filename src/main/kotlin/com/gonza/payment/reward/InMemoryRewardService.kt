package com.gonza.payment.reward

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class InMemoryRewardService : RewardService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val granted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override fun isFirstCharge(userId: UUID): Boolean = !granted.contains(userId)

    override fun grantInitialCoupon(userId: UUID): RewardResult {
        val added = granted.add(userId)
        if (!added) {
            log.warn("[시나리오6][Reward] 이미 지급된 사용자 userId=$userId")
            return RewardResult(granted = false, reason = "ALREADY_GRANTED")
        }
        val couponCode = "WELCOME-${userId.toString().take(8)}"
        log.info("[시나리오6][Reward] 최초 충전 쿠폰 지급 userId=$userId coupon=$couponCode")
        return RewardResult(granted = true, couponCode = couponCode)
    }
}
