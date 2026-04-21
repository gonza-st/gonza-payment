package com.gonza.payment.controller

import com.gonza.payment.dto.ChargeRequest
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.facade.ChargeFacade
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api")
class ChargeController(private val chargeFacade: ChargeFacade) {

    @PostMapping("/wallets/{userId}/charges")
    @ResponseStatus(HttpStatus.OK)
    fun chargePoints(
        @PathVariable userId: UUID,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: ChargeRequest
    ): ChargeResponse {
        return chargeFacade.chargePoints(userId, request.amount, idempotencyKey, request.channel)
    }
}
