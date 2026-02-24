package com.gonza.payment.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.gonza.payment.dto.CreateProductRequest
import com.gonza.payment.dto.ProductResponse
import com.gonza.payment.service.ProductService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@WebMvcTest(ProductController::class)
class ProductControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockBean lateinit var productService: ProductService

    @Test
    fun `POST products - returns 201`() {
        val productId = UUID.randomUUID()

        whenever(productService.createProduct(CreateProductRequest("Coffee", 4500L))).thenReturn(
            ProductResponse(id = productId, name = "Coffee", price = 4500L, isActive = true)
        )

        mockMvc.post("/api/products") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateProductRequest("Coffee", 4500L))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("Coffee") }
            jsonPath("$.price") { value(4500) }
        }
    }

    @Test
    fun `GET products - returns list`() {
        whenever(productService.getAllProducts()).thenReturn(
            listOf(ProductResponse(id = UUID.randomUUID(), name = "Coffee", price = 4500L, isActive = true))
        )

        mockMvc.get("/api/products").andExpect {
            status { isOk() }
            jsonPath("$[0].name") { value("Coffee") }
        }
    }
}
