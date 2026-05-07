package com.gonza.payment.notification

import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.User
import com.gonza.payment.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
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
class NotificationRouterTest {

    @Mock lateinit var userRepository: UserRepository

    private val userId = UUID.randomUUID()
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private fun router(time: LocalTime): NotificationRouter {
        val instant = ZonedDateTime.of(LocalDate.of(2026, 5, 1), time, zone).toInstant()
        return NotificationRouter(userRepository, Clock.fixed(instant, zone))
    }

    private fun stubUser(phoneNumber: String, vip: Boolean, marketingOptIn: Boolean) {
        val user = User(
            id = userId, name = "tester", phoneNumber = phoneNumber, email = "t@e.com",
            vip = vip, marketingOptIn = marketingOptIn
        )
        whenever(userRepository.findById(userId)).thenReturn(Optional.of(user))
    }

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
    fun `사용자 상태 X 시간대 조합에 맞는 채널을 반환한다`(
        phoneType: String,
        night: Boolean,
        vip: Boolean,
        marketingOptIn: Boolean,
        expectedChannelsCsv: String
    ) {
        val phoneNumber = if (phoneType == "FOREIGN") FOREIGN_PHONE else KOREAN_PHONE
        val time = if (night) NIGHT_TIME else DAY_TIME
        stubUser(phoneNumber, vip, marketingOptIn)

        val channels = router(time).route(userId)

        val expected = expectedChannelsCsv.split(";").map { NotificationChannel.valueOf(it.trim()) }
        assertThat(channels.toSet()).isEqualTo(expected.toSet())
    }

    companion object {
        private const val KOREAN_PHONE = "010-1234-5678"
        private const val FOREIGN_PHONE = "+1-415-555-1234"
        private val DAY_TIME: LocalTime = LocalTime.of(15, 0)
        private val NIGHT_TIME: LocalTime = LocalTime.of(2, 0)
    }
}
