package com.gonza.payment.service

import com.gonza.payment.domain.Gifticon
import com.gonza.payment.domain.GifticonProduct
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.domain.Wallet
import com.gonza.payment.exception.AlreadyConsumedException
import com.gonza.payment.exception.InsufficientBalanceException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.GifticonProductRepository
import com.gonza.payment.repository.GifticonRepository
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
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class GifticonServiceTest {

    @Mock lateinit var gifticonRepository: GifticonRepository
    @Mock lateinit var gifticonProductRepository: GifticonProductRepository
    @Mock lateinit var walletRepository: WalletRepository
    @Mock lateinit var pointLedgerRepository: PointLedgerRepository

    private lateinit var gifticonService: GifticonService
    private val userId = UUID.randomUUID()
    private val productId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        gifticonService = GifticonService(gifticonRepository, gifticonProductRepository, walletRepository, pointLedgerRepository)
    }

    @Test
    fun `purchaseGifticon - sufficient balance deducts and issues gifticon`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L)
        val wallet = Wallet(userId = userId, balance = 500L)

        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))
        whenever(walletRepository.subtractBalance(userId, 4500L)).thenReturn(1)
        doAnswer { it.arguments[0] }.whenever(gifticonRepository).save(any<Gifticon>())
        doAnswer { it.arguments[0] }.whenever(pointLedgerRepository).save(any())
        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = gifticonService.purchaseGifticon(userId, productId)

        assertThat(result.status).isEqualTo(GifticonStatus.ISSUED)
        assertThat(result.code).isNotBlank()
        assertThat(result.balance).isEqualTo(500L)
    }

    @Test
    fun `purchaseGifticon - insufficient balance throws exception`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L)

        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))
        whenever(walletRepository.subtractBalance(userId, 4500L)).thenReturn(0)

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId)
        }.isInstanceOf(InsufficientBalanceException::class.java)
    }

    @Test
    fun `purchaseGifticon - inactive product throws not found`() {
        val product = GifticonProduct(id = productId, name = "Coffee", price = 4500L, isActive = false)

        whenever(gifticonProductRepository.findById(productId)).thenReturn(Optional.of(product))

        assertThatThrownBy {
            gifticonService.purchaseGifticon(userId, productId)
        }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `consumeGifticon - ISSUED gifticon transitions to CONSUMED`() {
        val gifticonId = UUID.randomUUID()
        val gifticon = Gifticon(
            id = gifticonId,
            userId = userId,
            productId = productId,
            code = "ABC123"
        )

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
        val gifticon = Gifticon(
            id = gifticonId,
            userId = otherUserId,
            productId = productId,
            code = "ABC123"
        )

        whenever(gifticonRepository.findById(gifticonId)).thenReturn(Optional.of(gifticon))

        assertThatThrownBy {
            gifticonService.consumeGifticon(userId, gifticonId)
        }.isInstanceOf(NotFoundException::class.java)
    }
}
