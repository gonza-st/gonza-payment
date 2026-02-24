package com.gonza.payment.dto

import com.gonza.payment.domain.ChargeStatus
import java.util.UUID

data class ChargeRequest(val amount: Long)

data class ChargeResponse(
    val chargeId: UUID,
    val status: ChargeStatus,
    val balance: Long
)
