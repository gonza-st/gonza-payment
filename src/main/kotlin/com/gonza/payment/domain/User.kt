package com.gonza.payment.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class User(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val name: String,

    @Column(name = "phone_number", nullable = false)
    val phoneNumber: String,

    @Column(nullable = false)
    val email: String,

    @Column(nullable = false)
    val vip: Boolean = false,

    @Column(name = "marketing_opt_in", nullable = false)
    val marketingOptIn: Boolean = false,

    @Column(nullable = false)
    val createdAt: Instant = Instant.now()
)
