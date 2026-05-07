package com.gonza.payment.service

import com.gonza.payment.domain.Gifticon
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.domain.LedgerType
import com.gonza.payment.domain.PointLedger
import com.gonza.payment.domain.PurchaseRequest
import com.gonza.payment.domain.PurchaseStatus
import com.gonza.payment.dto.ConsumeGifticonResponse
import com.gonza.payment.dto.PurchaseGifticonResponse
import com.gonza.payment.exception.AlreadyConsumedException
import com.gonza.payment.exception.ConflictException
import com.gonza.payment.exception.InsufficientBalanceException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.GifticonProductRepository
import com.gonza.payment.repository.GifticonRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.PurchaseRequestRepository
import com.gonza.payment.repository.WalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class GifticonService(
    private val gifticonRepository: GifticonRepository,
    private val gifticonProductRepository: GifticonProductRepository,
    private val walletRepository: WalletRepository,
    private val pointLedgerRepository: PointLedgerRepository,
    private val purchaseRequestRepository: PurchaseRequestRepository,
    transactionManager: PlatformTransactionManager
) {
    private val txTemplate = TransactionTemplate(transactionManager)

    fun purchaseGifticon(userId: UUID, productId: UUID, idempotencyKey: String): PurchaseGifticonResponse {
        // Phase 1 (TX-1): PurchaseRequest 선점
        val result = createOrGetPurchaseRequest(userId, productId, idempotencyKey)

        return when (result) {
            is PurchaseCreationResult.AlreadyConfirmed -> result.response
            is PurchaseCreationResult.Created -> completePurchase(result.purchaseRequest, userId, productId)
        }
    }

    private fun createOrGetPurchaseRequest(
        userId: UUID,
        productId: UUID,
        idempotencyKey: String
    ): PurchaseCreationResult {
        return try {
            txTemplate.execute {
                val existing = purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                if (existing != null) {
                    return@execute resolveExisting(existing)
                }

                val purchaseRequest = PurchaseRequest(
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    productId = productId
                )
                purchaseRequestRepository.saveAndFlush(purchaseRequest)
                PurchaseCreationResult.Created(purchaseRequest)
            }!!
        } catch (e: DataIntegrityViolationException) {
            txTemplate.execute {
                val conflict = purchaseRequestRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    ?: throw IllegalStateException(
                        "PurchaseRequest not found after unique constraint violation for user=$userId, key=$idempotencyKey"
                    )
                resolveExisting(conflict)
            }!!
        }
    }

    private fun resolveExisting(existing: PurchaseRequest): PurchaseCreationResult {
        return when (existing.status) {
            PurchaseStatus.CONFIRMED -> {
                val gifticon = gifticonRepository.findById(existing.gifticonId!!)
                    .orElseThrow { IllegalStateException("Gifticon not found for confirmed purchase ${existing.id}") }
                val wallet = walletRepository.findById(existing.userId)
                    .orElseThrow { NotFoundException("Wallet not found") }
                PurchaseCreationResult.AlreadyConfirmed(
                    PurchaseGifticonResponse(
                        purchaseId = existing.id,
                        gifticonId = gifticon.id,
                        code = gifticon.code,
                        status = gifticon.status,
                        balance = wallet.balance
                    )
                )
            }
            PurchaseStatus.FAILED -> throw ConflictException("Purchase failed. Use a new idempotency key to retry.")
            else -> throw ConflictException("Purchase is still being processed")
        }
    }

    // Phase 2 (TX-2): 잔액 차감 → 기프티콘 생성 → 원장 기록 → PurchaseRequest CONFIRMED
    private fun completePurchase(
        purchaseRequest: PurchaseRequest,
        userId: UUID,
        productId: UUID
    ): PurchaseGifticonResponse {
        val product = gifticonProductRepository.findById(productId)
            .orElseThrow { NotFoundException("Product not found: $productId") }

        if (!product.isActive) {
            markPurchaseFailed(purchaseRequest)
            throw NotFoundException("Product is not active: $productId")
        }

        return try {
            txTemplate.execute {
                val updatedRows = walletRepository.subtractBalance(userId, product.price)
                if (updatedRows == 0) {
                    throw InsufficientBalanceException()
                }

                val gifticon = Gifticon(userId = userId, productId = productId, code = generateCode())
                gifticonRepository.save(gifticon)

                pointLedgerRepository.save(
                    PointLedger(
                        userId = userId,
                        type = LedgerType.PURCHASE,
                        refId = gifticon.id,
                        amountDelta = -product.price
                    )
                )

                purchaseRequest.gifticonId = gifticon.id
                purchaseRequest.status = PurchaseStatus.CONFIRMED
                purchaseRequest.updatedAt = Instant.now()
                purchaseRequestRepository.save(purchaseRequest)

                val wallet = walletRepository.findById(userId)
                    .orElseThrow { NotFoundException("Wallet not found") }

                PurchaseGifticonResponse(
                    purchaseId = purchaseRequest.id,
                    gifticonId = gifticon.id,
                    code = gifticon.code,
                    status = gifticon.status,
                    balance = wallet.balance
                )
            }!!
        } catch (e: InsufficientBalanceException) {
            markPurchaseFailed(purchaseRequest)
            throw e
        }
    }

    private fun markPurchaseFailed(purchaseRequest: PurchaseRequest) {
        txTemplate.execute {
            val fresh = purchaseRequestRepository.findById(purchaseRequest.id)
                .orElseThrow { IllegalStateException("PurchaseRequest ${purchaseRequest.id} disappeared") }
            fresh.status = PurchaseStatus.FAILED
            fresh.updatedAt = Instant.now()
            purchaseRequestRepository.save(fresh)
        }
    }

    fun consumeGifticon(userId: UUID, gifticonId: UUID): ConsumeGifticonResponse {
        return txTemplate.execute {
            val gifticon = gifticonRepository.findById(gifticonId)
                .orElseThrow { NotFoundException("Gifticon not found: $gifticonId") }

            if (gifticon.userId != userId) {
                throw NotFoundException("Gifticon not found: $gifticonId")
            }

            val updatedRows = gifticonRepository.consumeById(gifticonId, Instant.now())
            if (updatedRows == 0) {
                throw AlreadyConsumedException()
            }

            ConsumeGifticonResponse(
                gifticonId = gifticonId,
                status = GifticonStatus.CONSUMED
            )
        }!!
    }

    private fun generateCode(): String {
        return UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
    }
}
