package com.gonza.payment.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.dto.ChargeRequest
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.exception.UnprocessableException
import com.gonza.payment.service.ChargeService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(ChargeController::class)
class ChargeControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockBean lateinit var chargeService: ChargeService

    @Test
    fun `POST charges - successful charge returns 200`() {
        val userId = UUID.randomUUID()
        val chargeId = UUID.randomUUID()
        val idempotencyKey = "test-key"

        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = 5000L)
        )

        mockMvc.post("/api/wallets/$userId/charges") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = objectMapper.writeValueAsString(ChargeRequest(amount = 5000L))
        }.andExpect {
            status { isOk() }
            jsonPath("$.chargeId") { value(chargeId.toString()) }
            jsonPath("$.status") { value("COMPLETED") }
            jsonPath("$.balance") { value(5000) }
        }
    }

    @Test
    fun `POST charges - idempotent replay returns same result`() {
        val userId = UUID.randomUUID()
        val chargeId = UUID.randomUUID()
        val idempotencyKey = "idempotent-key"

        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = 5000L)
        )

        mockMvc.post("/api/wallets/$userId/charges") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = objectMapper.writeValueAsString(ChargeRequest(amount = 5000L))
        }.andExpect {
            status { isOk() }
            jsonPath("$.chargeId") { value(chargeId.toString()) }
        }
    }

    @Test
    fun `POST charges - different amount with same key returns 422`() {
        val userId = UUID.randomUUID()
        val idempotencyKey = "conflict-key"

        whenever(chargeService.chargePoints(userId, 3000L, idempotencyKey)).thenThrow(
            UnprocessableException("Idempotency key already used with different amount")
        )

        mockMvc.post("/api/wallets/$userId/charges") {
            contentType = MediaType.APPLICATION_JSON
            header("Idempotency-Key", idempotencyKey)
            content = objectMapper.writeValueAsString(ChargeRequest(amount = 3000L))
        }.andExpect {
            status { isUnprocessableEntity() }
        }
    }
}
