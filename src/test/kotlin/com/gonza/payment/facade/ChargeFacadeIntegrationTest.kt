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
    fun `SMS 채널 - charge COMPLETED - notification 저장되고 status=SENT`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenReturn(SmsSendResult(success = true, messageId = "sms-ok"))

        val response = chargeFacade.chargePoints(userId, 10_000L, "integ-key-1", NotificationChannel.SMS)

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(10_000L)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(1)

        val n = notifications.first()
        assertThat(n.status).isEqualTo(NotificationStatus.SENT)
        assertThat(n.channel).isEqualTo(NotificationChannel.SMS)
        assertThat(n.toUserId).isEqualTo(userId)
        assertThat(n.phoneNumber).isEqualTo("010-1234-5678")
        assertThat(n.email).isNull()
        assertThat(n.title).isEqualTo("포인트 충전 완료")
        assertThat(n.content).contains("10000P").contains("잔액 10000P")
    }

    @Test
    fun `SMS 실패 - notification status=FAILED, 충전은 COMPLETED 유지`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenReturn(SmsSendResult(success = false, errorCode = "SMS_SEND_FAILED"))

        val response = chargeFacade.chargePoints(userId, 5_000L, "integ-key-2", NotificationChannel.SMS)

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(5_000L)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(1)
        assertThat(notifications.first().status).isEqualTo(NotificationStatus.FAILED)
    }

    @Test
    fun `SMS 예외 - 충전은 여전히 COMPLETED 반환`() {
        whenever(smsClient.send(any(), any(), any()))
            .thenThrow(RuntimeException("SMS gateway timeout"))

        val response = chargeFacade.chargePoints(userId, 3_000L, "integ-key-3", NotificationChannel.SMS)

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(3_000L)
    }

    @Test
    fun `EMAIL 채널 - charge COMPLETED - notification email 컬럼에 저장되고 SENT`() {
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = true, messageId = "email-ok"))

        val response = chargeFacade.chargePoints(userId, 8_000L, "integ-key-4", NotificationChannel.EMAIL)

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(response.balance).isEqualTo(8_000L)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(1)

        val n = notifications.first()
        assertThat(n.status).isEqualTo(NotificationStatus.SENT)
        assertThat(n.channel).isEqualTo(NotificationChannel.EMAIL)
        assertThat(n.email).isEqualTo("notify@example.com")
        assertThat(n.phoneNumber).isNull()
    }

    @Test
    fun `EMAIL 실패 - notification status=FAILED, 충전은 COMPLETED 유지`() {
        whenever(emailClient.send(any(), any(), any()))
            .thenReturn(EmailSendResult(success = false, errorCode = "EMAIL_SEND_FAILED"))

        val response = chargeFacade.chargePoints(userId, 2_000L, "integ-key-5", NotificationChannel.EMAIL)

        assertThat(response.status).isEqualTo(ChargeStatus.COMPLETED)

        val notifications = notificationRepository.findAll()
        assertThat(notifications).hasSize(1)
        assertThat(notifications.first().status).isEqualTo(NotificationStatus.FAILED)
        assertThat(notifications.first().channel).isEqualTo(NotificationChannel.EMAIL)
    }
}
