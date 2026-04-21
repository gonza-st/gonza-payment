package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.service.ChargeService
import com.gonza.payment.service.NotificationService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ChargeFacadeTest {

    @Mock lateinit var chargeService: ChargeService
    @Mock lateinit var notificationService: NotificationService

    private lateinit var chargeFacade: ChargeFacade
    private val userId = UUID.randomUUID()
    private val idempotencyKey = "facade-key-001"

    @BeforeEach
    fun setUp() {
        chargeFacade = ChargeFacade(chargeService, notificationService)
    }

    @Test
    fun `chargePoints - COMPLETED 상태면 SMS 알림 발송`() {
        val chargeId = UUID.randomUUID()
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = 5000L)
        )

        val result = chargeFacade.chargePoints(userId, 5000L, idempotencyKey, NotificationChannel.SMS)

        assertThat(result.status).isEqualTo(ChargeStatus.COMPLETED)
        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.SMS))
    }

    @Test
    fun `chargePoints - EMAIL 채널로 요청 시 EMAIL 알림 발송`() {
        val chargeId = UUID.randomUUID()
        whenever(chargeService.chargePoints(userId, 7000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = 7000L)
        )

        chargeFacade.chargePoints(userId, 7000L, idempotencyKey, NotificationChannel.EMAIL)

        verify(notificationService).notify(eq(userId), any(), any(), eq(NotificationChannel.EMAIL))
    }

    @Test
    fun `chargePoints - 알림 예외가 터져도 ChargeResponse 정상 반환`() {
        val chargeId = UUID.randomUUID()
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = 5000L)
        )
        whenever(notificationService.notify(any(), any(), any(), any()))
            .thenThrow(RuntimeException("notify boom"))

        val result = chargeFacade.chargePoints(userId, 5000L, idempotencyKey, NotificationChannel.SMS)

        assertThat(result.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(result.balance).isEqualTo(5000L)
    }

    @Test
    fun `chargePoints - COMPLETED 아니면 알림 발송 안 함`() {
        val chargeId = UUID.randomUUID()
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.PG_APPROVED, balance = 0L)
        )

        chargeFacade.chargePoints(userId, 5000L, idempotencyKey, NotificationChannel.SMS)

        verify(notificationService, never()).notify(any(), any(), any(), any())
    }
}
