package com.gonza.payment.reward

import com.gonza.payment.event.ChargeCompletedEvent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeRewardListenerTest {

    @Mock lateinit var rewardService: RewardService

    private lateinit var listener: ChargeRewardListener
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        listener = ChargeRewardListener(rewardService)
    }

    @Test
    fun `최초 충전이면 환영 쿠폰 지급 호출`() {
        whenever(rewardService.isFirstCharge(userId)).thenReturn(true)
        whenever(rewardService.grantInitialCoupon(userId)).thenReturn(RewardResult(granted = true, couponCode = "C-1"))

        listener.on(ChargeCompletedEvent(UUID.randomUUID(), userId, 5000L, 5000L))

        verify(rewardService).grantInitialCoupon(userId)
    }

    @Test
    fun `최초 충전 아니면 호출 안 함`() {
        whenever(rewardService.isFirstCharge(userId)).thenReturn(false)

        listener.on(ChargeCompletedEvent(UUID.randomUUID(), userId, 5000L, 5000L))

        verify(rewardService, never()).grantInitialCoupon(any())
    }

    @Test
    fun `grantInitialCoupon 예외가 터져도 리스너 자체는 정상 종료`() {
        whenever(rewardService.isFirstCharge(userId)).thenReturn(true)
        whenever(rewardService.grantInitialCoupon(userId)).thenThrow(RuntimeException("reward boom"))

        listener.on(ChargeCompletedEvent(UUID.randomUUID(), userId, 5000L, 5000L))
    }
}
