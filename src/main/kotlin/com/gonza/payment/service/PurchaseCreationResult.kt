package com.gonza.payment.service

import com.gonza.payment.domain.PurchaseRequest
import com.gonza.payment.dto.PurchaseGifticonResponse

sealed interface PurchaseCreationResult {
    data class Created(val purchaseRequest: PurchaseRequest) : PurchaseCreationResult
    data class AlreadyConfirmed(val response: PurchaseGifticonResponse) : PurchaseCreationResult
}
