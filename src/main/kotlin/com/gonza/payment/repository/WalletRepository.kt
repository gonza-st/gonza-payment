package com.gonza.payment.repository

import com.gonza.payment.domain.Wallet
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface WalletRepository : JpaRepository<Wallet, UUID> {

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Wallet w SET w.balance = w.balance + :amount, w.version = w.version + 1 WHERE w.userId = :userId")
    fun addBalance(userId: UUID, amount: Long): Int

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Wallet w SET w.balance = w.balance - :amount, w.version = w.version + 1 WHERE w.userId = :userId AND w.balance >= :amount")
    fun subtractBalance(userId: UUID, amount: Long): Int
}
