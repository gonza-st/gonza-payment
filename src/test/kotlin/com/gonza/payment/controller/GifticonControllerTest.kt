package com.gonza.payment.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.dto.ConsumeGifticonResponse
import com.gonza.payment.dto.PurchaseGifticonRequest
import com.gonza.payment.dto.PurchaseGifticonResponse
import com.gonza.payment.service.GifticonService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(GifticonController::class)
class GifticonControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockBean lateinit var gifticonService: GifticonService

    @Test
    fun `POST purchase - returns 201 with gifticon`() {
        val userId = UUID.randomUUID()
        val productId = UUID.randomUUID()
        val gifticonId = UUID.randomUUID()

        whenever(gifticonService.purchaseGifticon(userId, productId)).thenReturn(
            PurchaseGifticonResponse(
                gifticonId = gifticonId,
                code = "ABC123",
                status = GifticonStatus.ISSUED,
                balance = 500L
            )
        )

        mockMvc.post("/api/users/$userId/gifticons") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(PurchaseGifticonRequest(productId = productId))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.gifticonId") { value(gifticonId.toString()) }
            jsonPath("$.code") { value("ABC123") }
            jsonPath("$.status") { value("ISSUED") }
            jsonPath("$.balance") { value(500) }
        }
    }

    @Test
    fun `POST consume - returns 200 with consumed status`() {
        val userId = UUID.randomUUID()
        val gifticonId = UUID.randomUUID()

        whenever(gifticonService.consumeGifticon(userId, gifticonId)).thenReturn(
            ConsumeGifticonResponse(gifticonId = gifticonId, status = GifticonStatus.CONSUMED)
        )

        mockMvc.post("/api/users/$userId/gifticons/$gifticonId/consume") {
            contentType = MediaType.APPLICATION_JSON
        }.andExpect {
            status { isOk() }
            jsonPath("$.gifticonId") { value(gifticonId.toString()) }
            jsonPath("$.status") { value("CONSUMED") }
        }
    }
}
