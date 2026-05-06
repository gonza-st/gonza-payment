package com.gonza.payment.datalake

import com.gonza.payment.event.ChargeCompletedEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ChargeDataLakeListenerTest {

    @Mock lateinit var dataLakeClient: DataLakeClient

    private lateinit var listener: ChargeDataLakeListener
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        listener = ChargeDataLakeListener(dataLakeClient)
    }

    @Test
    fun `이벤트 수신 시 ChargeEvent 적재`() {
        val chargeId = UUID.randomUUID()
        val captor = argumentCaptor<ChargeEvent>()

        listener.on(ChargeCompletedEvent(chargeId, userId, 5000L, 5000L))

        verify(dataLakeClient).publish(captor.capture())
        assertThat(captor.firstValue.chargeId).isEqualTo(chargeId)
        assertThat(captor.firstValue.userId).isEqualTo(userId)
        assertThat(captor.firstValue.amount).isEqualTo(5000L)
        assertThat(captor.firstValue.balance).isEqualTo(5000L)
    }

    @Test
    fun `publish 예외가 터져도 리스너 자체는 정상 종료`() {
        whenever(dataLakeClient.publish(org.mockito.kotlin.any())).thenThrow(RuntimeException("kafka boom"))

        listener.on(ChargeCompletedEvent(UUID.randomUUID(), userId, 5000L, 5000L))
    }
}
