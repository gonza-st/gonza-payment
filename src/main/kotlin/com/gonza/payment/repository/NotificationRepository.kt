package com.gonza.payment.repository

import com.gonza.payment.domain.Notification
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface NotificationRepository : JpaRepository<Notification, UUID>
