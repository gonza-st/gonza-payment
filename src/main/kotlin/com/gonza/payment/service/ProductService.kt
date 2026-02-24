package com.gonza.payment.service

import com.gonza.payment.domain.GifticonProduct
import com.gonza.payment.dto.CreateProductRequest
import com.gonza.payment.dto.ProductResponse
import com.gonza.payment.repository.GifticonProductRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ProductService(
    private val gifticonProductRepository: GifticonProductRepository
) {

    @Transactional
    fun createProduct(request: CreateProductRequest): ProductResponse {
        val product = GifticonProduct(
            name = request.name,
            price = request.price
        )
        gifticonProductRepository.save(product)
        return product.toResponse()
    }

    @Transactional(readOnly = true)
    fun getAllProducts(): List<ProductResponse> {
        return gifticonProductRepository.findAllByIsActiveTrue().map { it.toResponse() }
    }

    private fun GifticonProduct.toResponse() = ProductResponse(
        id = id,
        name = name,
        price = price,
        isActive = isActive
    )
}
