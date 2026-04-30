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

    @BeforeEach
    fun setUp() {
        stubChargeCompleted()
    }

    // ── [시나리오4] 라우팅 조합 폭발: phone × night × vip × marketingOptIn = 16 케이스 ──
    // ※ 이 한 메서드가 ChargeFacade 의 알림 라우팅 정책을 통째로 검증한다.
    //   알림 정책 한 줄만 바뀌어도 16개 중 다수가 한꺼번에 빨개지며, 충전 로직과 무관하게
    //   "충전 테스트가 깨졌다" 는 신호를 만든다.
    @ParameterizedTest(name = "[{index}] phone={0} night={1} vip={2} marketingOptIn={3} → {4}")
    @CsvSource(
        // phone,    night, vip,   marketingOptIn, expectedChannels (콤마 구분)
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

    // ── 충전 본연의 책임: 알림 정책과 무관하게 보장돼야 하는 동작 ──────────────────
    // ※ 시나리오4의 증상: 아래 두 케이스(충전 본연)와 위 16 케이스(라우팅)가 한 클래스에
    //   섞여 있다는 사실 자체가 두 관심사 분리가 안 됐다는 증거다.

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
