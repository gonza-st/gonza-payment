package com.gonza.payment.notification

import com.gonza.payment.domain.Notification
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.NotificationStatus
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
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChannelDispatcherTest {

    @Mock lateinit var notificationService: NotificationService

    private lateinit var dispatcher: ChannelDispatcher
    private val userId = UUID.randomUUID()
    private val chargeId = UUID.randomUUID()
    private val backoffSleeps = mutableListOf<Long>()

    @BeforeEach
    fun setUp() {
        dispatcher = ChannelDispatcher(notificationService) { backoffSleeps += it }
    }

    private fun notificationOf(channel: NotificationChannel, status: NotificationStatus) = Notification(
        title = "t", content = "c", channel = channel, toUserId = userId, status = status
    )

    @Test
    fun `SMS - 첫 시도 SENT 면 재시도 안 함, 백오프 없음`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.SENT))

        dispatcher.send(userId, "t", "c", NotificationChannel.SMS, chargeId)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).isEmpty()
    }

    @Test
    fun `SMS - FAILED 후 SENT 면 재시도 1회 후 종료`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.FAILED))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.SENT))

        dispatcher.send(userId, "t", "c", NotificationChannel.SMS, chargeId)

        verify(notificationService, times(2)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).containsExactly(100L)
    }

    @Test
    fun `SMS - 3회 모두 FAILED 면 호출 3번, 백오프 100, 200ms`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenReturn(notificationOf(NotificationChannel.SMS, NotificationStatus.FAILED))

        dispatcher.send(userId, "t", "c", NotificationChannel.SMS, chargeId)

        verify(notificationService, times(3)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
        assertThat(backoffSleeps).containsExactly(100L, 200L)
    }

    @Test
    fun `EMAIL - 2회 시도, 지수 백오프 200ms`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.EMAIL)))
            .thenReturn(notificationOf(NotificationChannel.EMAIL, NotificationStatus.FAILED))

        dispatcher.send(userId, "t", "c", NotificationChannel.EMAIL, chargeId)

        verify(notificationService, times(2)).notify(any(), any(), any(), eq(NotificationChannel.EMAIL))
        assertThat(backoffSleeps).contains(200L)
    }

    @Test
    fun `KAKAO_ALIMTALK - FAILED 여도 재시도 안 함`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK)))
            .thenReturn(notificationOf(NotificationChannel.KAKAO_ALIMTALK, NotificationStatus.FAILED))

        dispatcher.send(userId, "t", "c", NotificationChannel.KAKAO_ALIMTALK, chargeId)

        verify(notificationService, times(1)).notify(any(), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK))
        assertThat(backoffSleeps).isEmpty()
    }

    @Test
    fun `SLACK PUSH MARKETING_HUB - 재시도 안 함`() {
        listOf(NotificationChannel.SLACK, NotificationChannel.PUSH, NotificationChannel.MARKETING_HUB).forEach { ch ->
            whenever(notificationService.notify(any(), any(), any(), eq(ch)))
                .thenReturn(notificationOf(ch, NotificationStatus.FAILED))

            dispatcher.send(userId, "t", "c", ch, chargeId)

            verify(notificationService, times(1)).notify(any(), any(), any(), eq(ch))
        }
    }

    @Test
    fun `예외가 터져도 다음 시도까지 진행하고 마지막엔 최종 실패`() {
        whenever(notificationService.notify(any(), any(), any(), eq(NotificationChannel.SMS)))
            .thenThrow(RuntimeException("boom"))

        dispatcher.send(userId, "t", "c", NotificationChannel.SMS, chargeId)

        verify(notificationService, times(3)).notify(any(), any(), any(), eq(NotificationChannel.SMS))
    }
}
