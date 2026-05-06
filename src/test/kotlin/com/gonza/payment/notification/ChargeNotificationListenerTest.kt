package com.gonza.payment.notification

import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.event.ChargeCompletedEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ChargeNotificationListenerTest {

    @Mock lateinit var router: NotificationRouter
    @Mock lateinit var dispatcher: ChannelDispatcher

    private lateinit var listener: ChargeNotificationListener

    @BeforeEach
    fun setUp() {
        listener = ChargeNotificationListener(router, dispatcher)
    }

    @Test
    fun `이벤트 수신 시 router 결과대로 dispatcher send 가 호출된다`() {
        val userId = UUID.randomUUID()
        val chargeId = UUID.randomUUID()
        whenever(router.route(userId)).thenReturn(
            listOf(NotificationChannel.SMS, NotificationChannel.EMAIL, NotificationChannel.KAKAO_ALIMTALK)
        )

        listener.on(ChargeCompletedEvent(chargeId, userId, 5000L, 5000L))

        verify(dispatcher).send(eq(userId), any(), any(), eq(NotificationChannel.SMS), eq(chargeId))
        verify(dispatcher).send(eq(userId), any(), any(), eq(NotificationChannel.EMAIL), eq(chargeId))
        verify(dispatcher).send(eq(userId), any(), any(), eq(NotificationChannel.KAKAO_ALIMTALK), eq(chargeId))
    }
}
