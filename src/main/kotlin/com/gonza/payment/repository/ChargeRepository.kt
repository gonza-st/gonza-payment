package com.gonza.payment.repository

import com.gonza.payment.domain.Charge
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChargeRepository : JpaRepository<Charge, UUID> {
    fun findByUserIdAndIdempotencyKey(userId: UUID, idempotencyKey: String): Charge?
}
