package com.gonza.payment.repository

import com.gonza.payment.domain.Gifticon
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface GifticonRepository : JpaRepository<Gifticon, UUID> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Gifticon g SET g.status = 'CONSUMED', g.consumedAt = :consumedAt WHERE g.id = :id AND g.status = 'ISSUED'")
    fun consumeById(id: UUID, consumedAt: Instant): Int
}
