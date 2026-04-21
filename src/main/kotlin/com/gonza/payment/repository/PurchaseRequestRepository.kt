package com.gonza.payment.repository

import com.gonza.payment.domain.PurchaseRequest
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PurchaseRequestRepository : JpaRepository<PurchaseRequest, UUID> {
    fun findByUserIdAndIdempotencyKey(userId: UUID, idempotencyKey: String): PurchaseRequest?
}
