package com.gonza.payment.repository

import com.gonza.payment.domain.PointLedger
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PointLedgerRepository : JpaRepository<PointLedger, UUID> {
    fun findAllByUserId(userId: UUID): List<PointLedger>
}
