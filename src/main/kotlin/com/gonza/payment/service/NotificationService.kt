package com.gonza.payment.service

import com.gonza.payment.domain.Notification
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.email.EmailClient
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.kakao.KakaoAlimtalkClient
import com.gonza.payment.marketing.MarketingHubClient
import com.gonza.payment.push.PushClient
import com.gonza.payment.repository.NotificationRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.slack.SlackClient
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
    private val emailClient: EmailClient,
    private val pushClient: PushClient,
    private val kakaoAlimtalkClient: KakaoAlimtalkClient,
    private val slackClient: SlackClient,
    private val marketingHubClient: MarketingHubClient,
    transactionManager: PlatformTransactionManager
) {
    private val txTemplate = TransactionTemplate(transactionManager)

    fun notify(userId: UUID, title: String, content: String, channel: NotificationChannel): Notification {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("User not found: $userId") }

        // Phase 1 (TX-1): REQUESTED 상태로 저장
        val saved = txTemplate.execute {
            notificationRepository.save(
                Notification(
                    title = title,
                    content = content,
                    channel = channel,
                    toUserId = userId,
                    phoneNumber = if (channel == NotificationChannel.SMS || channel == NotificationChannel.KAKAO_ALIMTALK) user.phoneNumber else null,
                    email = if (channel == NotificationChannel.EMAIL) user.email else null
                )
            )
        }!!

        // Phase 2 (TX 밖): 외부 호출 — DB 커넥션 미점유
        val success = when (channel) {
            NotificationChannel.SMS -> smsClient.send(user.phoneNumber, title, content).success
            NotificationChannel.EMAIL -> emailClient.send(user.email, title, content).success
            NotificationChannel.PUSH -> pushClient.send(userId.toString(), title, content).success
            NotificationChannel.KAKAO_ALIMTALK -> kakaoAlimtalkClient.send(user.phoneNumber, title, content).success
            NotificationChannel.SLACK -> slackClient.send(userId.toString(), title, content).success
            NotificationChannel.MARKETING_HUB -> marketingHubClient.send(userId.toString(), title, content).success
        }

        // Phase 3 (TX-2): 결과 반영
        return txTemplate.execute {
            val notification = notificationRepository.findById(saved.id)
                .orElseThrow { IllegalStateException("Notification ${saved.id} disappeared") }

            notification.status = if (success) {
                NotificationStatus.SENT
            } else {
                NotificationStatus.FAILED
            }
            notification.updatedAt = Instant.now()
            notificationRepository.save(notification)
        }!!
    }
}
