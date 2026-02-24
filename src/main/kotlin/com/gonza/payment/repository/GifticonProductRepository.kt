package com.gonza.payment.repository

import com.gonza.payment.domain.GifticonProduct
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GifticonProductRepository : JpaRepository<GifticonProduct, UUID> {
    fun findAllByIsActiveTrue(): List<GifticonProduct>
}
