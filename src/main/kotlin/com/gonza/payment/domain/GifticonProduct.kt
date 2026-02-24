package com.gonza.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "gifticon_products")
class GifticonProduct(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val price: Long,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true
)
