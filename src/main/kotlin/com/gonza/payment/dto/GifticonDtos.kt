package com.gonza.payment.dto

import com.gonza.payment.domain.GifticonStatus
import java.util.UUID

data class PurchaseGifticonRequest(val productId: UUID)

data class PurchaseGifticonResponse(
    val purchaseId: UUID,
    val gifticonId: UUID,
    val code: String,
    val status: GifticonStatus,
    val balance: Long
)

data class ConsumeGifticonResponse(
    val gifticonId: UUID,
    val status: GifticonStatus
)
