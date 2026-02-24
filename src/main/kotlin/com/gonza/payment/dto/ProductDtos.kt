package com.gonza.payment.dto

import java.util.UUID

data class CreateProductRequest(val name: String, val price: Long)

data class ProductResponse(
    val id: UUID,
    val name: String,
    val price: Long,
    val isActive: Boolean
)
