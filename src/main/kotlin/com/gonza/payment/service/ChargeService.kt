package com.gonza.payment.service

import com.gonza.payment.domain.Charge
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.LedgerType
import com.gonza.payment.domain.PointLedger
import com.gonza.payment.dto.ChargeResponse
import com.gonza.payment.exception.ConflictException
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.exception.PgPaymentFailedException
import com.gonza.payment.exception.UnprocessableException
import com.gonza.payment.pg.PgApproveResult
import com.gonza.payment.pg.PgClient
import com.gonza.payment.repository.ChargeRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.WalletRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class ChargeService(
    private val chargeRepository: ChargeRepository,
    private val walletRepository: WalletRepository,
    private val pointLedgerRepository: PointLedgerRepository,
    private val pgClient: PgClient,
    transactionManager: PlatformTransactionManager
) {
    private val txTemplate = TransactionTemplate(transactionManager)

    fun chargePoints(userId: UUID, amount: Long, idempotencyKey: String): ChargeResponse {
        // Phase 1 (TX-1): Charge 선점
        val result = createOrGetCharge(userId, amount, idempotencyKey)

        return when (result) {
            is ChargeCreationResult.AlreadyCompleted -> result.response
            is ChargeCreationResult.Created -> {
                // Phase 2 (TX 밖): PG 호출 — DB 커넥션 미점유
                val pgResult = pgClient.approve(idempotencyKey, amount)

                // Phase 3 (TX-2): 결과 반영 + 잔액 증가 + 원장 기록
                completeCharge(result.charge.id, pgResult, userId, amount)
            }
        }
    }

    private fun createOrGetCharge(
        userId: UUID,
        amount: Long,
        idempotencyKey: String
    ): ChargeCreationResult {
        return try {
            txTemplate.execute {
                val existing = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                if (existing != null) {
                    return@execute resolveExisting(existing, amount)
                }

                val charge = Charge(
                    userId = userId,
                    idempotencyKey = idempotencyKey,
                    amount = amount
                )
                chargeRepository.saveAndFlush(charge)
                ChargeCreationResult.Created(charge)
            }!!
        } catch (e: DataIntegrityViolationException) {
            // TX-1이 unique constraint 위반으로 롤백됨
            // 새 TX에서 경쟁에 이긴 레코드를 재조회
            txTemplate.execute {
                val conflict = chargeRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    ?: throw IllegalStateException(
                        "Charge not found after unique constraint violation for user=$userId, key=$idempotencyKey"
                    )
                resolveExisting(conflict, amount)
            }!!
        }
    }

    private fun resolveExisting(existing: Charge, requestedAmount: Long): ChargeCreationResult {
        if (existing.amount != requestedAmount) {
            throw UnprocessableException("Idempotency key already used with different amount")
        }

        return when (existing.status) {
            ChargeStatus.COMPLETED -> {
                val wallet = walletRepository.findById(existing.userId)
                    .orElseThrow { NotFoundException("Wallet not found") }
                ChargeCreationResult.AlreadyCompleted(
                    ChargeResponse(
                        chargeId = existing.id,
                        status = existing.status,
                        balance = wallet.balance
                    )
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

    private fun completeCharge(
        chargeId: UUID,
        pgResult: PgApproveResult,
        userId: UUID,
        amount: Long
    ): ChargeResponse {
        return txTemplate.execute {
            val charge = chargeRepository.findById(chargeId)
                .orElseThrow { IllegalStateException("Charge $chargeId disappeared") }

            // 다른 요청이 이미 처리했는지 확인
            if (charge.status != ChargeStatus.REQUESTED) {
                val wallet = walletRepository.findById(userId)
                    .orElseThrow { NotFoundException("Wallet not found for user $userId") }
                return@execute ChargeResponse(
                    chargeId = charge.id,
                    status = charge.status,
                    balance = wallet.balance
                )
            }

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

            ChargeResponse(
                chargeId = charge.id,
                status = charge.status,
                balance = wallet.balance
            )
        }!!
    }
}
