package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.Notification
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
import com.gonza.payment.domain.User
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
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
    private val backoffSleeps = mutableListOf<Long>()

    private fun facade(time: LocalTime = LocalTime.of(15, 0)): ChargeFacade {
        val instant = ZonedDateTime.of(LocalDate.of(2026, 5, 1), time, zone).toInstant()
        return ChargeFacade(
            chargeService,
            notificationService,
            userRepository,
            channelTimeoutMs = 0L,
            clock = Clock.fixed(instant, zone),
            backoff = { backoffSleeps += it }
        )
    }

    private fun stubUser(
        phoneNumber: String = KOREAN_PHONE,
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

    private fun stubAllNotifySent() {
        whenever(notificationService.notify(any(), any(), any(), any())).thenAnswer { inv ->
            notificationOf(inv.arguments[3] as NotificationChannel, NotificationStatus.SENT)
        }
    }

    private fun notificationOf(channel: NotificationChannel, status: NotificationStatus) = Notification(
        title = "t",
        content = "c",
        channel = channel,
        toUserId = userId,
        status = status
    )

    @BeforeEach
    fun setUp() {
        stubChargeCompleted()
        stubAllNotifySent()
        backoffSleeps.clear()
    }

    // ── [시나리오4] 라우팅 조합 폭발: phone × night × vip × marketingOptIn = 16 케이스 ──
    @ParameterizedTest(name = "[{index}] phone={0} night={1} vip={2} marketingOptIn={3} → {4}")
    @CsvSource(
        "NORMAL,  false, false, false, 'SMS;EMAIL'",
        "NORMAL,  true,  false, false, 'PUSH;EMAIL'",
        "NORMAL,  false, true,  false, 'SMS;EMAIL;KAKAO_ALIMTALK'",
        "NORMAL,  false, false, true,  'SMS;EMAIL;SLACK'",
        "NORMAL,  true,  true,  false, 'PUSH;EMAIL;KAKAO_ALIMTALK'",
        "NORMAL,  true,  false, true,  'PUSH;EMAIL;SLACK'",
        "NORMAL,  false, true,  true,  'SMS;EMAIL;KAKAO_ALIMTALK;SLACK'",
        "NORMAL,  true,  true,  true,  'PUSH;EMAIL;KAKAO_ALIMTALK;SLACK'",
        "FOREIGN, false, false, false, 'EMAIL'",
        "FOREIGN, true,  false, false, 'EMAIL'",
        "FOREIGN, false, true,  false, 'EMAIL;KAKAO_ALIMTALK'",
        "FOREIGN, false, false, true,  'EMAIL;SLACK'",
        "FOREIGN, true,  true,  false, 'EMAIL;KAKAO_ALIMTALK'",
        "FOREIGN, true,  false, true,  'EMAIL;SLACK'",
        "FOREIGN, false, true,  true,  'EMAIL;KAKAO_ALIMTALK;SLACK'",
        "FOREIGN, true,  true,  true,  'EMAIL;KAKAO_ALIMTALK;SLACK'"
    )
    fun `충전 완료 시 사용자 상태 X 시간대 조합에 맞는 채널로만 발송한다`(
        phoneType: String,
        night: Boolean,
        vip: Boolean,
        marketingOptIn: Boolean,
        expectedChannelsCsv: String
    ) {
        val phoneNumber = if (phoneType == "FOREIGN") FOREIGN_PHONE else KOREAN_PHONE
        val time = if (night) NIGHT_TIME else DAY_TIME
        stubUser(phoneNumber = phoneNumber, vip = vip, marketingOptIn = marketingOptIn)

        facade(time = time).chargePoints(userId, 5000L, idempotencyKey)

        val expected = expectedChannelsCsv.split(";").map { NotificationChannel.valueOf(it.trim()) }.toSet()
        val captor = argumentCaptor<NotificationChannel>()
        verify(notificationService, atLeast(1)).notify(any(), any(), any(), captor.capture())
        assertThat(captor.allValues.toSet()).isEqualTo(expected)
    }

    // ── [시나리오5] 채널마다 다른 SLA/재시도 정책이 한 곳에 뭉친다 ─────────────────

    @Test
    fun `SMS 첫 시도 FAILED 면 재시도 후 SENT 면 그 자리에서 종료`() {
        stubUser()
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.FAILED))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.SENT))

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(2)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).containsExactly(100L)
    }

    @Test
    fun `SMS 3회 모두 FAILED 면 SMS 호출 3번에 백오프 100, 200ms 후 포기`() {
        stubUser()
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.FAILED))

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(3)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).containsExactly(100L, 200L)
    }

    @Test
    fun `EMAIL 정책 - 2회 시도, 지수 백오프 200ms`() {
        stubUser()
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.EMAIL)))
            .thenReturn(notificationOf(NotificationChannel.EMAIL, NotificationStatus.FAILED))

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(2)).notify(any(), any(), any(), eq(NotificationChannel.EMAIL))
        assertThat(backoffSleeps).contains(200L)
    }

    @Test
    fun `KAKAO_ALIMTALK FAILED 여도 재시도 안 함 (템플릿 거부 가능성)`() {
        stubUser(vip = true)
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK)))
            .thenReturn(notificationOf(NotificationChannel.KAKAO_ALIMTALK, NotificationStatus.FAILED))

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
    }

    @Test
    fun `SLACK FAILED 여도 재시도 안 함 (마케팅 베스트 에포트)`() {
        stubUser(marketingOptIn = true)
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SLACK)))
            .thenReturn(notificationOf(NotificationChannel.SLACK, NotificationStatus.FAILED))

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.SLACK))
    }

    @Test
    fun `PUSH FAILED 여도 재시도 안 함 (베스트 에포트)`() {
        stubUser()
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.PUSH)))
            .thenReturn(notificationOf(NotificationChannel.PUSH, NotificationStatus.FAILED))

        facade(time = NIGHT_TIME).chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.PUSH))
    }

    @Test
    fun `SMS 첫 시도 SENT 면 재시도 안 함, 백오프 호출 없음`() {
        stubUser()

        facade().chargePoints(userId, 5000L, idempotencyKey)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).isEmpty()
    }

    // ── 충전 본연의 책임: 알림 정책과 무관하게 보장돼야 하는 동작 ──────────────────

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

    companion object {
        private const val KOREAN_PHONE = "010-1234-5678"
        private const val FOREIGN_PHONE = "+1-415-555-1234"
        private val DAY_TIME: LocalTime = LocalTime.of(15, 0)
        private val NIGHT_TIME: LocalTime = LocalTime.of(2, 0)
    }
}
