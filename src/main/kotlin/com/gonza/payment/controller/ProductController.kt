package com.gonza.payment.controller

import com.gonza.payment.dto.CreateProductRequest
import com.gonza.payment.dto.ProductResponse
import com.gonza.payment.service.ProductService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class ProductController(private val productService: ProductService) {

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    fun createProduct(@RequestBody request: CreateProductRequest): ProductResponse {
        return productService.createProduct(request)
    }

    @GetMapping("/products")
    fun getAllProducts(): List<ProductResponse> {
        return productService.getAllProducts()
    }
}
