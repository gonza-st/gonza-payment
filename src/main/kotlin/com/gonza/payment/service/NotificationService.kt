package com.gonza.payment.service

import com.gonza.payment.domain.Notification
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.NotificationRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.sms.SmsClient
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class NotificationService(
    private val notificationRepository: NotificationRepository,
    private val userRepository: UserRepository,
    private val smsClient: SmsClient,
    transactionManager: PlatformTransactionManager
) {
    private val txTemplate = TransactionTemplate(transactionManager)

    fun notify(userId: UUID, title: String, content: String): Notification {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("User not found: $userId") }

        // Phase 1 (TX-1): REQUESTED 상태로 저장
        val saved = txTemplate.execute {
            notificationRepository.save(
                Notification(
                    title = title,
                    content = content,
                    toUserId = userId,
                    phoneNumber = user.phoneNumber
                )
            )
        }!!

        // Phase 2 (TX 밖): 외부 SMS 호출 — DB 커넥션 미점유
        val result = smsClient.send(user.phoneNumber, title, content)

        // Phase 3 (TX-2): 결과 반영
        return txTemplate.execute {
            val notification = notificationRepository.findById(saved.id)
                .orElseThrow { IllegalStateException("Notification ${saved.id} disappeared") }

            notification.status = if (result.success) {
                NotificationStatus.SENT
            } else {
                NotificationStatus.FAILED
            }
            notification.updatedAt = Instant.now()
            notificationRepository.save(notification)
        }!!
    }
}
