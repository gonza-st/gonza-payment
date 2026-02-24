package com.gonza.payment.controller

import com.gonza.payment.dto.ConsumeGifticonResponse
import com.gonza.payment.dto.PurchaseGifticonRequest
import com.gonza.payment.dto.PurchaseGifticonResponse
import com.gonza.payment.service.GifticonService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class GifticonController(private val gifticonService: GifticonService) {

    @PostMapping("/users/{userId}/gifticons")
    @ResponseStatus(HttpStatus.CREATED)
    fun purchaseGifticon(
        @PathVariable userId: UUID,
        @RequestBody request: PurchaseGifticonRequest
    ): PurchaseGifticonResponse {
        return gifticonService.purchaseGifticon(userId, request.productId)
    }

    @PostMapping("/users/{userId}/gifticons/{gifticonId}/consume")
    fun consumeGifticon(
        @PathVariable userId: UUID,
        @PathVariable gifticonId: UUID
    ): ConsumeGifticonResponse {
        return gifticonService.consumeGifticon(userId, gifticonId)
    }
}
