package com.gonza.payment.datalake

import java.util.UUID

interface DataLakeClient {
    fun publish(event: ChargeEvent)
}

data class ChargeEvent(
    val chargeId: UUID,
    val userId: UUID,
    val amount: Long,
    val balance: Long
)
