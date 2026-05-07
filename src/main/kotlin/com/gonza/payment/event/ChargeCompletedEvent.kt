package com.gonza.payment.event

import java.time.Instant
import java.util.UUID

data class ChargeCompletedEvent(
    val chargeId: UUID,
    val userId: UUID,
    val amount: Long,
    val balance: Long,
    val occurredAt: Instant = Instant.now()
)
