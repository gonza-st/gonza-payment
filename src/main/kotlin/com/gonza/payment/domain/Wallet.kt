package com.gonza.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.util.UUID

@Entity
@Table(name = "wallets")
class Wallet(
    @Id
    @Column(name = "user_id")
    val userId: UUID,

    @Column(nullable = false)
    var balance: Long = 0,

    @Version
    @Column(nullable = false)
    var version: Long = 0
)
