package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import com.gonza.payment.email.EmailClient
import com.gonza.payment.email.EmailSendResult
import com.gonza.payment.repository.NotificationRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import com.gonza.payment.sms.SmsClient
import com.gonza.payment.sms.SmsSendResult
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

@SpringBootTest
@Testcontainers
@Tag("docker")
class ChargeFacadeIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("points")
            withUsername("points")
            withPassword("points")
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("pg.mock.fail-rate") { "0.0" }
            registry.add("pg.mock.delay-ms") { "0" }
            registry.add("sms.mock.fail-rate") { "0.0" }
            registry.add("sms.mock.delay-ms") { "0" }
            registry.add("email.mock.fail-rate") { "0.0" }
            registry.add("email.mock.delay-ms") { "0" }
        }
    }

    @Autowired lateinit var chargeFacade: ChargeFacade
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var notificationRepository: NotificationRepository
    @MockBean lateinit var smsClient: SmsClient
    @MockBean lateinit var emailClient: EmailClient

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        notificationRepository.deleteAll()
        walletRepository.deleteAll()
        userRepository.deleteAll()

        val user = User(name = "NotifyUser", phoneNumber = "010-1234-5678", email = "notify@example.com")
        userRepository.save(user)
        userId = user.id
        walletRepository.save(Wallet(userId = userId, balance = 0L))
    }

    @Test
    fun `charge COMPLETED - SMS와 EMAIL notification 2건 저장되고 모두 SENT`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenReturn(SmsSendResult(success = true, messageId = "sms-ok"))
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = true, messageId = "email-ok"))

        val response = chargeFacade.chargePoints(userId, 10_000L, "integ-key-1")

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(10_000L)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(2)

        val sms = notifications.first { it.channel == NotificationChannel.SMS }
        assertThat(sms.status).isEqualTo(NotificationStatus.SENT)
        assertThat(sms.toUserId).isEqualTo(userId)
        assertThat(sms.phoneNumber).isEqualTo("010-1234-5678")
        assertThat(sms.email).isNull()
        assertThat(sms.title).isEqualTo("포인트 충전 완료")
        assertThat(sms.content).contains("10000P").contains("잔액 10000P")

        val email = notifications.first { it.channel == NotificationChannel.EMAIL }
        assertThat(email.status).isEqualTo(NotificationStatus.SENT)
        assertThat(email.email).isEqualTo("notify@example.com")
        assertThat(email.phoneNumber).isNull()
    }

    @Test
    fun `SMS 실패 + EMAIL 성공 - SMS는 FAILED, EMAIL은 SENT, 충전은 COMPLETED 유지`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenReturn(SmsSendResult(success = false, errorCode = "SMS_SEND_FAILED"))
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = true, messageId = "email-ok"))

        val response = chargeFacade.chargePoints(userId, 5_000L, "integ-key-2")

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(5_000L)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(2)
        assertThat(notifications.first { it.channel == NotificationChannel.SMS }.status)
            .isEqualTo(NotificationStatus.FAILED)
        assertThat(notifications.first { it.channel == NotificationChannel.EMAIL }.status)
            .isEqualTo(NotificationStatus.SENT)
    }

    @Test
    fun `SMS 예외 + EMAIL 성공 - 충전은 COMPLETED, EMAIL notification은 SENT로 저장`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenThrow(RuntimeException("SMS gateway timeout"))
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = true, messageId = "email-ok"))

        val response = chargeFacade.chargePoints(userId, 3_000L, "integ-key-3")

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(3_000L)

        val notifications = notificationRepository.findAll()
        val email = notifications.first { it.channel == NotificationChannel.EMAIL }
        assertThat(email.status).isEqualTo(NotificationStatus.SENT)
    }

    @Test
    fun `EMAIL 실패 + SMS 성공 - EMAIL은 FAILED, SMS는 SENT, 충전은 COMPLETED 유지`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenReturn(SmsSendResult(success = true, messageId = "sms-ok"))
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = false, errorCode = "EMAIL_SEND_FAILED"))

        val response = chargeFacade.chargePoints(userId, 2_000L, "integ-key-4")

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(2)
        assertThat(notifications.first { it.channel == NotificationChannel.SMS }.status)
            .isEqualTo(NotificationStatus.SENT)
        assertThat(notifications.first { it.channel == NotificationChannel.EMAIL }.status)
            .isEqualTo(NotificationStatus.FAILED)
    }
}
