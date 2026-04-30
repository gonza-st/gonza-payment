package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.User
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeFacadeTest {

    @Mock lateinit var chargeService: ChargeService
    @Mock lateinit var notificationService: NotificationService
    @Mock lateinit var userRepository: UserRepository

    private val userId = UUID.randomUUID()
    private val idempotencyKey = "facade-key-001"
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun facade(time: LocalTime = LocalTime.of(15, 0)): ChargeFacade {
        val instant = ZonedDateTime.of(LocalDate.of(2026, 5, 1), time, zone).toInstant()
        return ChargeFacade(
            chargeService,
            notificationService,
            userRepository,
            channelTimeoutMs = 0L,
            clock = Clock.fixed(instant, zone)
        )
    }

    private fun stubUser(
        phoneNumber: String = "010-1234-5678",
        vip: Boolean = false,
        marketingOptIn: Boolean = false
    ) {
        val user = User(id = userId, name = "tester", phoneNumber = phoneNumber, email = "t@e.com", vip = vip, marketingOptIn = marketingOptIn)
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
    }

    private fun stubChargeCompleted() {
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = UUID.randomUUID(), status = ChargeStatus.COMPLETED, balance = 5000L)
        )
    }

    @BeforeEach
    fun setUp() {
        stubChargeCompleted()
    }

    @Test
    fun `기본 사용자 - 주간 - SMS, EMAIL 발송`() {
        stubUser()

        facade(time = LocalTime.of(15, 0)).chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SMS))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.PUSH))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.SLACK))
    }

    @Test
    fun `야간 - SMS 대신 PUSH 로 대체`() {
        stubUser()

        facade(time = LocalTime.of(23, 30)).chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.PUSH))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.SMS))
    }

    @Test
    fun `VIP 회원 - 알림톡 추가 발송`() {
        stubUser(vip = true)

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SMS))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
    }

    @Test
    fun `마케팅 수신 동의자 - SLACK 추가 발송`() {
        stubUser(marketingOptIn = true)

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SLACK))
    }

    @Test
    fun `외국 번호 사용자 - SMS, PUSH 제외 EMAIL 만`() {
        stubUser(phoneNumber = "+1-415-555-1234")

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.PUSH))
    }

    @Test
    fun `외국 번호 + VIP + 마케팅 동의 - EMAIL, KAKAO, SLACK`() {
        stubUser(phoneNumber = "+1-415-555-1234", vip = true, marketingOptIn = true)

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SLACK))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.PUSH))
    }

    @Test
    fun `야간 + VIP + 마케팅 동의 - PUSH, EMAIL, KAKAO, SLACK`() {
        stubUser(vip = true, marketingOptIn = true)

        facade(time = LocalTime.of(2, 0)).chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.PUSH))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SLACK))
        verify(notificationService, never()).notify(any(), any(), any(), eq(NotificationChannel.SMS))
    }

    @Test
    fun `notify 예외가 터져도 다른 채널은 계속 호출되고 ChargeResponse 정상 반환`() {
        stubUser()
        whenever(notificationService.notify(eq(userId), any(), any(), eq(NotificationChannel.SMS)))
            .thenThrow(RuntimeException("sms boom"))

        val result = facade().chargePoints(userId, 5000L, idempotencyKey)

        assertThat(result.status).isEqualTo(ChargeStatus.COMPLETED)
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
    }

    @Test
    fun `COMPLETED 아니면 알림 발송 안 함 그리고 userRepository 조회도 안 함`() {
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = UUID.randomUUID(), status = ChargeStatus.PG_APPROVED, balance = 0L)
        )

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, never()).notify(any(), any(), any(), any())
        verify(userRepository, never()).findById(any())
    }

}
