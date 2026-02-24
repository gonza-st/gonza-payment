package com.gonza.payment.service

import com.gonza.payment.domain.Charge
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.LedgerType
import com.gonza.payment.domain.PointLedger
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.exception.ConflictException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.exception.PgPaymentFailedException
import com.gonza.payment.pg.PgClient
import com.gonza.payment.repository.ChargeRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class ChargeService(
    private val chargeRepository: ChargeRepository,
    private val walletRepository: WalletRepository,
    private val pointLedgerRepository: PointLedgerRepository,
    private val pgClient: PgClient
) {

    @Transactional
    fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
        val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)

        if (existing != null) {
            return handleExistingCharge(existing, amount)
        }

        val charge = Charge(
            userId = userId,
            idempotencyKey = idempotencyKey,
            amount = amount
        )
        chargeRepository.save(charge)

        val pgResult = pgClient.approve(idempotencyKey, amount)

        if (!pgResult.success) {
            charge.status = ChargeStatus.FAILED
            charge.updatedAt = Instant.now()
            chargeRepository.save(charge)
            throw PgPaymentFailedException(pgResult.errorCode ?: "UNKNOWN")
        }

        charge.pgTransactionId = pgResult.pgTransactionId
        charge.status = ChargeStatus.PG_APPROVED
        charge.updatedAt = Instant.now()

        val updatedRows = walletRepository.addBalance(userId, amount)
        if (updatedRows == 0) {
            throw NotFoundException("Wallet not found for user $userId")
        }

        pointLedgerRepository.save(
            PointLedger(
                userId = userId,
                type = LedgerType.CHARGE,
                refId = charge.id,
                amountDelta = amount
            )
        )

        charge.status = ChargeStatus.COMPLETED
        charge.updatedAt = Instant.now()
        chargeRepository.save(charge)

        val wallet = walletRepository.findById(userId)
            .orElseThrow { NotFoundException("Wallet not found for user $userId") }

        return ChargeResponse(
            chargeId = charge.id,
            status = charge.status,
            balance = wallet.balance
        )
    }

    private fun handleExistingCharge(existing: Charge, requestedAmount: Long): ChargeResponse {
        if (existing.amount != requestedAmount) {
            throw ConflictException("Idempotency key already used with different amount")
        }

        when (existing.status) {
            ChargeStatus.COMPLETED -> {
                val wallet = walletRepository.findById(existing.userId)
                    .orElseThrow { NotFoundException("Wallet not found") }
                return ChargeResponse(
                    chargeId = existing.id,
                    status = existing.status,
                    balance = wallet.balance
                )
            }
            ChargeStatus.FAILED -> {
                throw ConflictException("Charge failed. Use a new idempotency key to retry.")
            }
            else -> {
                throw ConflictException("Charge is still being processed")
            }
        }
    }
}
