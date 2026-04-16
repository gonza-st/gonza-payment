package com.gonza.payment.service

import com.gonza.payment.domain.Gifticon
import com.gonza.payment.domain.GifticonProduct
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.domain.PurchaseRequest
import com.gonza.payment.domain.PurchaseStatus
import com.gonza.payment.domain.Wallet
import com.gonza.payment.exception.AlreadyConsumedException
import com.gonza.payment.exception.ConflictException
import com.gonza.payment.exception.InsufficientBalanceException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.GifticonProductRepository
import com.gonza.payment.repository.GifticonRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.PurchaseRequestRepository
import com.gonza.payment.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GifticonServiceTest {

    @Mock lateinit var gifticonRepository: GifticonRepository
    @Mock lateinit var gifticonProductRepository: GifticonProductRepository
    @Mock lateinit var walletRepository: WalletRepository
    @Mock lateinit var pointLedgerRepository: PointLedgerRepository
    @Mock lateinit var purchaseRequestRepository: PurchaseRequestRepository
    @Mock lateinit var transactionManager: PlatformTransactionManager
    @Mock lateinit var transactionStatus: TransactionStatus

    private lateinit var gifticonService: GifticonService
    private val userId = UUID.randomUUID()
    private val productId = UUID.randomUUID()
    private val idempotencyKey = "test-purchase-key-001"

    @BeforeEach
    fun setUp() {
        whenever(transactionManager.getTransaction(any())).thenReturn(transactionStatus)
        gifticonService = GifticonService(
            gifticonRepository, gifticonProductRepository, walletRepository,
            pointLedgerRepository, purchaseRequestRepository, transactionManager
        )
    }

    @Test
    fun `purchaseGifticon - successful purchase creates purchase request and issues gifticon`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L)
        val wallet = Wallet(userId = userId, balance = 500L)

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(null)
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).saveAndFlush(any<PurchaseRequest>())
        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))
        whenever(walletRepository.subtractBalance(userId, 4500L)).thenReturn(1)
        doAnswer { it.arguments[0] }.whenever(gifticonRepository).save(any<Gifticon>())
        doAnswer { it.arguments[0] }.whenever(pointLedgerRepository).save(any())
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).save(any<PurchaseRequest>())
        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = gifticonService.purchaseGifticon(userId, productId, idempotencyKey)

        assertThat(result.status).isEqualTo(GifticonStatus.ISSUED)
        assertThat(result.code).isNotBlank()
        assertThat(result.balance).isEqualTo(500L)
        assertThat(result.purchaseId).isNotNull()
    }

    @Test
    fun `purchaseGifticon - same idempotency key returns same result without duplicate purchase`() {
        val gifticonId = UUID.randomUUID()
        val existingPurchase = PurchaseRequest(
            userId = userId,
            idempotencyKey = idempotencyKey,
            productId = productId,
            gifticonId = gifticonId,
            status = PurchaseStatus.CONFIRMED
        )
        val gifticon = Gifticon(id = gifticonId, userId = userId, productId = productId, code = "EXISTCODE")
        val wallet = Wallet(userId = userId, balance = 500L)

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
            .thenReturn(existingPurchase)
        whenever(gifticonRepository.findById(gifticonId)).thenReturn(Optional.of(gifticon))
        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = gifticonService.purchaseGifticon(userId, productId, idempotencyKey)

        assertThat(result.purchaseId).isEqualTo(existingPurchase.id)
        assertThat(result.gifticonId).isEqualTo(gifticonId)
        assertThat(result.status).isEqualTo(GifticonStatus.ISSUED)
    }

    @Test
    fun `purchaseGifticon - failed purchase with same key throws conflict`() {
        val existingPurchase = PurchaseRequest(
            userId = userId,
            idempotencyKey = idempotencyKey,
            productId = productId,
            status = PurchaseStatus.FAILED
        )

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
            .thenReturn(existingPurchase)

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId, idempotencyKey)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("failed")
    }

    @Test
    fun `purchaseGifticon - insufficient balance marks purchase as failed and throws`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L)
        val purchaseRequest = PurchaseRequest(
            userId = userId,
            idempotencyKey = idempotencyKey,
            productId = productId
        )

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(null)
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).saveAndFlush(any<PurchaseRequest>())
        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))
        whenever(walletRepository.subtractBalance(userId, 4500L)).thenReturn(0)
        whenever(purchaseRequestRepository.findById(any<UUID>())).thenReturn(Optional.of(purchaseRequest))
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).save(any<PurchaseRequest>())

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId, idempotencyKey)
        }.isInstanceOf(InsufficientBalanceException::class.java)
    }

    @Test
    fun `purchaseGifticon - inactive product marks purchase as failed and throws`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L, isActive = false)
        val purchaseRequest = PurchaseRequest(
            userId = userId,
            idempotencyKey = idempotencyKey,
            productId = productId
        )

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)).thenReturn(null)
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).saveAndFlush(any<PurchaseRequest>())
        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))
        whenever(purchaseRequestRepository.findById(any<UUID>())).thenReturn(Optional.of(purchaseRequest))
        doAnswer { it.arguments[0] }.whenever(purchaseRequestRepository).save(any<PurchaseRequest>())

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId, idempotencyKey)
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `purchaseGifticon - REQUESTED status purchase throws conflict (still processing)`() {
        val existingPurchase = PurchaseRequest(
            userId = userId,
            idempotencyKey = idempotencyKey,
            productId = productId,
            status = PurchaseStatus.REQUESTED
        )

        whenever(purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey))
            .thenReturn(existingPurchase)

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId, idempotencyKey)
        }.isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("being processed")
    }

    @Test
    fun `consumeGifticon - ISSUED gifticon transitions to CONSUMED`() {
        val gifticonId = UUID.randomUUID()
        val gifticon = Gifticon(id = gifticonId, userId = userId, productId = productId, code = "ABC123")

        whenever(gifticonRepository.findById(gifticonId)).thenReturn(Optional.of(gifticon))
        whenever(gifticonRepository.consumeById(any(), any())).thenReturn(1)

        val result = gifticonService.consumeGifticon(userId, gifticonId)

        assertThat(result.status).isEqualTo(GifticonStatus.CONSUMED)
    }

    @Test
    fun `consumeGifticon - already consumed throws conflict`() {
        val gifticonId = UUID.randomUUID()
        val gifticon = Gifticon(
            id = gifticonId,
            userId = userId,
            productId = productId,
            code = "ABC123",
            status = GifticonStatus.CONSUMED
        )

        whenever(gifticonRepository.findById(gifticonId)).thenReturn(Optional.of(gifticon))
        whenever(gifticonRepository.consumeById(any(), any())).thenReturn(0)

        assertThatThrownBy {
            gifticonService.consumeGifticon(userId, gifticonId)
        }.isInstanceOf(AlreadyConsumedException::class.java)
    }

    @Test
    fun `consumeGifticon - wrong user throws not found`() {
        val gifticonId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val gifticon = Gifticon(id = gifticonId, userId = otherUserId, productId = productId, code = "ABC123")

        whenever(gifticonRepository.findById(gifticonId)).thenReturn(Optional.of(gifticon))

        assertThatThrownBy {
            gifticonService.consumeGifticon(userId, gifticonId)
        }.isInstanceOf(NotFoundException::class.java)
    }
}
