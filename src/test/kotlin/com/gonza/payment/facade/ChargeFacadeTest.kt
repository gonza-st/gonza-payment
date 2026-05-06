package com.gonza.payment.facade

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.event.ChargeCompletedEvent
import com.gonza.payment.fds.FdsClient
import com.gonza.payment.fds.FdsResult
import com.gonza.payment.service.ChargeService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChargeFacadeTest {

    @Mock lateinit var chargeService: ChargeService
    @Mock lateinit var fdsClient: FdsClient
    @Mock lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var chargeFacade: ChargeFacade
    private val userId = UUID.randomUUID()
    private val idempotencyKey = "facade-key-001"

    @BeforeEach
    fun setUp() {
        whenever(fdsClient.check(any(), any())).thenReturn(FdsResult(approved = true))
        chargeFacade = ChargeFacade(chargeService, fdsClient, eventPublisher)
    }

    private fun stubChargeCompleted(amount: Long, chargeId: UUID = UUID.randomUUID()) {
        whenever(chargeService.chargePoints(userId, amount, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = chargeId, status = ChargeStatus.COMPLETED, balance = amount)
        )
    }

    @Test
    fun `COMPLETED 시 ChargeCompletedEvent 를 발행한다`() {
        val chargeId = UUID.randomUUID()
        stubChargeCompleted(5000L, chargeId)
        val captor = argumentCaptor<ChargeCompletedEvent>()

        chargeFacade.chargePoints(userId, 5000L, idempotencyKey)

        verify(eventPublisher).publishEvent(captor.capture())
        assertThat(captor.firstValue.chargeId).isEqualTo(chargeId)
        assertThat(captor.firstValue.userId).isEqualTo(userId)
        assertThat(captor.firstValue.amount).isEqualTo(5000L)
        assertThat(captor.firstValue.balance).isEqualTo(5000L)
    }

    @Test
    fun `COMPLETED 가 아니면 이벤트를 발행하지 않는다`() {
        whenever(chargeService.chargePoints(userId, 5000L, idempotencyKey)).thenReturn(
            ChargeResponse(chargeId = UUID.randomUUID(), status = ChargeStatus.PG_APPROVED, balance = 0L)
        )

        chargeFacade.chargePoints(userId, 5000L, idempotencyKey)

        verify(eventPublisher, never()).publishEvent(any<ChargeCompletedEvent>())
    }

    @Test
    fun `보안팀 - amount 가 임계값(1_000_000) 이상이면 FDS 사전 검사`() {
        stubChargeCompleted(1_000_000L)

        chargeFacade.chargePoints(userId, 1_000_000L, idempotencyKey)

        verify(fdsClient).check(userId, 1_000_000L)
    }

    @Test
    fun `보안팀 - amount 가 임계값 미만이면 FDS 호출 안 함`() {
        stubChargeCompleted(5000L)

        chargeFacade.chargePoints(userId, 5000L, idempotencyKey)

        verify(fdsClient, never()).check(any(), any())
    }

    @Test
    fun `보안팀 - FDS 거부 시 IllegalStateException, chargeService 미호출, 이벤트 미발행`() {
        whenever(fdsClient.check(any(), any())).thenReturn(FdsResult(approved = false, reason = "BLACKLIST"))

        assertThatThrownBy {
            chargeFacade.chargePoints(userId, 2_000_000L, idempotencyKey)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BLACKLIST")

        verify(chargeService, never()).chargePoints(any(), any(), any())
        verify(eventPublisher, never()).publishEvent(any<ChargeCompletedEvent>())
    }

    @Test
    fun `ChargeFacade 는 알림_리워드_데이터레이크 의존성을 직접 갖지 않는다`() {
        // EDA 정신 검증 — 이 테스트가 컴파일된다는 사실 자체가 분리의 증거.
        val constructorParams = ChargeFacade::class.constructors.single().parameters.map { it.type.toString() }
        assertThat(constructorParams).noneMatch { it.contains("NotificationService") || it.contains("RewardService") || it.contains("DataLakeClient") || it.contains("UserRepository") }
    }
}
