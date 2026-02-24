package com.gonza.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "gifticons")
class Gifticon(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "product_id", nullable = false)
    val productId: UUID,

    @Column(nullable = false, unique = true)
    val code: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: GifticonStatus = GifticonStatus.ISSUED,

    @Column(name = "issued_at", nullable = false)
    val issuedAt: Instant = Instant.now(),

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null
)
