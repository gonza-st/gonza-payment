package com.gonza.payment.service

import com.gonza.payment.domain.Gifticon
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.domain.LedgerType
import com.gonza.payment.domain.PointLedger
import com.gonza.payment.dto.ConsumeGifticonResponse
import com.gonza.payment.dto.PurchaseGifticonResponse
import com.gonza.payment.exception.AlreadyConsumedException
import com.gonza.payment.exception.InsufficientBalanceException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.GifticonProductRepository
import com.gonza.payment.repository.GifticonRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class GifticonService(
    private val gifticonRepository: GifticonRepository,
    private val gifticonProductRepository: GifticonProductRepository,
    private val walletRepository: WalletRepository,
    private val pointLedgerRepository: PointLedgerRepository
) {

    @Transactional
    fun purchaseGifticon(userId: UUID, productId: UUID): PurchaseGifticonResponse {
        val product = gifticonProductRepository.findById(productId)
            .orElseThrow { NotFoundException("Product not found: $productId") }

        if (!product.isActive) {
            throw NotFoundException("Product is not active: $productId")
        }

        val updatedRows = walletRepository.subtractBalance(userId, product.price)
        if (updatedRows == 0) {
            throw InsufficientBalanceException()
        }

        val gifticon = Gifticon(
            userId = userId,
            productId = productId,
            code = generateCode()
        )
        gifticonRepository.save(gifticon)

        pointLedgerRepository.save(
            PointLedger(
                userId = userId,
                type = LedgerType.PURCHASE,
                refId = gifticon.id,
                amountDelta = -product.price
            )
        )

        val wallet = walletRepository.findById(userId)
            .orElseThrow { NotFoundException("Wallet not found") }

        return PurchaseGifticonResponse(
            gifticonId = gifticon.id,
            code = gifticon.code,
            status = gifticon.status,
            balance = wallet.balance
        )
    }

    @Transactional
    fun consumeGifticon(userId: UUID, gifticonId: UUID): ConsumeGifticonResponse {
        val gifticon = gifticonRepository.findById(gifticonId)
            .orElseThrow { NotFoundException("Gifticon not found: $gifticonId") }

        if (gifticon.userId != userId) {
            throw NotFoundException("Gifticon not found: $gifticonId")
        }

        val updatedRows = gifticonRepository.consumeById(gifticonId, Instant.now())
        if (updatedRows == 0) {
            throw AlreadyConsumedException()
        }

        return ConsumeGifticonResponse(
            gifticonId = gifticonId,
            status = GifticonStatus.CONSUMED
        )
    }

    private fun generateCode(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
    }
}
