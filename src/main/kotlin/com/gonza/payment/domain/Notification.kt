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
@Table(name = "notifications")
class Notification(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false, length = 1000)
    val content: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: NotificationStatus = NotificationStatus.REQUESTED,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val channel: NotificationChannel,

    @Column(name = "to_user_id", nullable = false)
    val toUserId: UUID,

    @Column(name = "phone_number", nullable = true)
    val phoneNumber: String? = null,

    @Column(name = "email", nullable = true)
    val email: String? = null,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
