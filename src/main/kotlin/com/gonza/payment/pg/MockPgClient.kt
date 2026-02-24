package com.gonza.payment.pg

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockPgClient(
    @Value("\${pg.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${pg.mock.delay-ms:0}") private val delayMs: Long
) : PgClient {

    override fun approve(idempotencyKey: String, amount: Long): PgApproveResult {
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        if (Random.nextDouble() < failRate) {
            return PgApproveResult(
                success = false,
                errorCode = "DECLINED"
            )
        }

        return PgApproveResult(
            success = true,
            pgTransactionId = "pg-${UUID.randomUUID()}"
        )
    }
}
