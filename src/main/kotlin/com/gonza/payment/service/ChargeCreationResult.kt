package com.gonza.payment.service

import com.gonza.payment.domain.Charge
import com.gonza.payment.dto.ChargeResponse

sealed interface ChargeCreationResult {
    data class Created(val charge: Charge) : ChargeCreationResult
    data class AlreadyCompleted(val response: ChargeResponse) : ChargeCreationResult
}
