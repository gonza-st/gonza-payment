package com.gonza.payment.service

import com.gonza.payment.domain.Charge
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.Wallet
import com.gonza.payment.exception.ConflictException
import com.gonza.payment.exception.PgPaymentFailedException
import com.gonza.payment.exception.UnprocessableException
import com.gonza.payment.pg.PgApproveResult
import com.gonza.payment.pg.PgClient
import com.gonza.payment.repository.ChargeRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ChargeServiceTest {

    @Mock lateinit var chargeRepository: ChargeRepository
    @Mock lateinit var walletRepository: WalletRepository
    @Mock lateinit var pointLedgerRepository: PointLedgerRepository
    @Mock lateinit var pgClient: PgClient
    @Mock lateinit var transactionManager: PlatformTransactionManager
    @Mock lateinit var transactionStatus: TransactionStatus

    private lateinit var chargeService: ChargeService
    private val userId = UUID.randomUUID()
    private val idempotencyKey = "test-key-001"

    @BeforeEach
    fun setUp() {
        whenever(transactionManager.getTransaction(any())).thenReturn(transactionStatus)
        chargeService = ChargeService(
            chargeRepository, walletRepository, pointLedgerRepository,
            pgClient, transactionManager
        )
    }

    @Test
    fun `chargePoints - successful charge increases balance and records ledger`() {
        val amount = 5000L
        val wallet = Wallet(userId = userId, balance = 5000L)

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(null)
        doAnswer { it.arguments[0] }.whenever(chargeRepository).saveAndFlush(any<Charge>())
        whenever(pgClient.approve(idempotencyKey, amount)).thenReturn(
            PgApproveResult(success = true, pgTransactionId = "pg-123")
        )
        whenever(chargeRepository.findById(any<UUID>())).thenAnswer {
            Optional.of(Charge(userId = userId, idempotencyKey = idempotencyKey, amount = amount))
        }
        doAnswer { it.arguments[0] }.whenever(chargeRepository).save(any<Charge>())
        whenever(walletRepository.addBalance(userId, amount)).thenReturn(1)
        doAnswer { it.arguments[0] }.whenever(pointLedgerRepository).save(any())
        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = chargeService.chargePoints(userId, amount, idempotencyKey)

        assertThat(result.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(result.balance).isEqualTo(5000L)
    }

    @Test
    fun `chargePoints - same idempotency key returns same result without duplicate charge`() {
        val amount = 5000L
        val existingCharge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = amount,
            status = ChargeStatus.COMPLETED
        )
        val wallet = Wallet(userId = userId, balance = 5000L)

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(existingCharge)
        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = chargeService.chargePoints(userId, amount, idempotencyKey)

        assertThat(result.chargeId).isEqualTo(existingCharge.id)
        assertThat(result.status).isEqualTo(ChargeStatus.COMPLETED)
    }

    @Test
    fun `chargePoints - same idempotency key with different amount throws 422`() {
        val existingCharge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = 5000L,
            status = ChargeStatus.COMPLETED
        )

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(existingCharge)

        assertThatThrownBy {
            chargeService.chargePoints(userId, 3000L, idempotencyKey)
        }.isInstanceOf(UnprocessableException::class.java)
            .hasMessageContaining("different amount")
    }

    @Test
    fun `chargePoints - PG failure sets charge to FAILED and does not change balance`() {
        val amount = 5000L

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(null)
        doAnswer { it.arguments[0] }.whenever(chargeRepository).saveAndFlush(any<Charge>())
        whenever(pgClient.approve(idempotencyKey, amount)).thenReturn(
            PgApproveResult(success = false, errorCode = "DECLINED")
        )
        whenever(chargeRepository.findById(any<UUID>())).thenAnswer {
            Optional.of(Charge(userId = userId, idempotencyKey = idempotencyKey, amount = amount))
        }
        doAnswer { it.arguments[0] }.whenever(chargeRepository).save(any<Charge>())

        assertThatThrownBy {
            chargeService.chargePoints(userId, amount, idempotencyKey)
        }.isInstanceOf(PgPaymentFailedException::class.java)
    }

    @Test
    fun `chargePoints - failed charge with same key throws conflict`() {
        val existingCharge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = 5000L,
            status = ChargeStatus.FAILED
        )

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(existingCharge)

        assertThatThrownBy {
            chargeService.chargePoints(userId, 5000L, idempotencyKey)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("failed")
    }

    @Test
    fun `chargePoints - REQUESTED status charge throws conflict (still processing)`() {
        val existingCharge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = 5000L,
            status = ChargeStatus.REQUESTED
        )

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(existingCharge)

        assertThatThrownBy {
            chargeService.chargePoints(userId, 5000L, idempotencyKey)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("being processed")
    }

    @Test
    fun `chargePoints - PG_APPROVED status charge throws conflict (still processing)`() {
        val existingCharge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = 5000L,
            status = ChargeStatus.PG_APPROVED
        )

        whenever(chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(existingCharge)

        assertThatThrownBy {
            chargeService.chargePoints(userId, 5000L, idempotencyKey)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("being processed")
    }
}
