package com.gonza.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "purchase_requests",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "idempotency_key"])]
)
class PurchaseRequest(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "idempotency_key", nullable = false)
    val idempotencyKey: String,

    @Column(name = "product_id", nullable = false)
    val productId: UUID,

    @Column(name = "gifticon_id")
    var gifticonId: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: PurchaseStatus = PurchaseStatus.REQUESTED,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(nullable = false)
    var updatedAt: Instant = Instant.now()
)
