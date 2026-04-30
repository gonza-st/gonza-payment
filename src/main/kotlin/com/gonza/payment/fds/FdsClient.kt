package com.gonza.payment.fds

import java.util.UUID

interface FdsClient {
    fun check(userId: UUID, amount: Long): FdsResult
}

data class FdsResult(val approved: Boolean, val reason: String? = null)
