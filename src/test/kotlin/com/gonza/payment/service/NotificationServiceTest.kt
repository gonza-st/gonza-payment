package com.gonza.payment.service

import com.gonza.payment.domain.Notification
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.domain.User
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.NotificationRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.sms.SmsClient
import com.gonza.payment.sms.SmsSendResult
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.mockito.junit.jupiter.MockitoSettings
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock lateinit var notificationRepository: NotificationRepository
    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var smsClient: SmsClient
    @Mock lateinit var transactionManager: PlatformTransactionManager
    @Mock lateinit var transactionStatus: TransactionStatus

    private lateinit var notificationService: NotificationService
    private val userId = UUID.randomUUID()
    private val phoneNumber = "010-1234-5678"

    @BeforeEach
    fun setUp() {
        whenever(transactionManager.getTransaction(any())).thenReturn(transactionStatus)
        notificationService = NotificationService(
            notificationRepository, userRepository, smsClient, transactionManager
        )
    }

    @Test
    fun `notify - SMS success marks notification as SENT`() {
        val user = User(id = userId, name = "tester", phoneNumber = phoneNumber)

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        doAnswer { it.arguments[0] }.whenever(notificationRepository).save(any<Notification>())
        whenever(notificationRepository.findById(any<UUID>())).thenAnswer {
            Optional.of(
                Notification(
                    id = it.arguments[0] as UUID,
                    title = "t", content = "c",
                    toUserId = userId, phoneNumber = phoneNumber
                )
            )
        }
        whenever(smsClient.send(phoneNumber, "t", "c")).thenReturn(
            SmsSendResult(success = true, messageId = "sms-1")
        )

        val result = notificationService.notify(userId, "t", "c")

        assertThat(result.status).isEqualTo(NotificationStatus.SENT)
        assertThat(result.phoneNumber).isEqualTo(phoneNumber)
    }

    @Test
    fun `notify - SMS failure marks notification as FAILED`() {
        val user = User(id = userId, name = "tester", phoneNumber = phoneNumber)

        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
        doAnswer { it.arguments[0] }.whenever(notificationRepository).save(any<Notification>())
        whenever(notificationRepository.findById(any<UUID>())).thenAnswer {
            Optional.of(
                Notification(
                    id = it.arguments[0] as UUID,
                    title = "t", content = "c",
                    toUserId = userId, phoneNumber = phoneNumber
                )
            )
        }
        whenever(smsClient.send(phoneNumber, "t", "c")).thenReturn(
            SmsSendResult(success = false, errorCode = "SMS_SEND_FAILED")
        )

        val result = notificationService.notify(userId, "t", "c")

        assertThat(result.status).isEqualTo(NotificationStatus.FAILED)
    }

    @Test
    fun `notify - unknown user throws NotFoundException`() {
        whenever(userRepository.findById(userId)).thenReturn(Optional.empty())

        assertThatThrownBy {
            notificationService.notify(userId, "t", "c")
        }.isInstanceOf(NotFoundException::class.java)
    }
}
