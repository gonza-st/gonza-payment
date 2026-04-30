package com.gonza.payment.reward

import java.util.UUID

interface RewardService {
    fun isFirstCharge(userId: UUID): Boolean
    fun grantInitialCoupon(userId: UUID): RewardResult
}

data class RewardResult(val granted: Boolean, val couponCode: String? = null, val reason: String? = null)
