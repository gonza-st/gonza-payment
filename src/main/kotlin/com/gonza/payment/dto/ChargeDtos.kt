package com.gonza.payment.dto

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import java.util.UUID

data class ChargeRequest(
    val amount: Long,
    val channel: NotificationChannel = NotificationChannel.SMS
)

data class ChargeResponse(
    val chargeId: UUID,
    val status: ChargeStatus,
    val balance: Long
)
