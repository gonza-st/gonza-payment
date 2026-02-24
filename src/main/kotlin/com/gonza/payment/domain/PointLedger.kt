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
@Table(name = "point_ledgers")
class PointLedger(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: LedgerType,

    @Column(name = "ref_id", nullable = false)
    val refId: UUID,

    @Column(name = "amount_delta", nullable = false)
    val amountDelta: Long,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
