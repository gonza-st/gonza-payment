package com.gonza.payment.pg

interface PgClient {
    fun approve(idempotencyKey: String, amount: Long): PgApproveResult
}

data class PgApproveResult(
    val success: Boolean,
    val pgTransactionId: String? = null,
    val errorCode: String? = null
)
