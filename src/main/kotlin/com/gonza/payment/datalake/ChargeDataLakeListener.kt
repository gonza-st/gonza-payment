package com.gonza.payment.datalake

import com.gonza.payment.event.ChargeCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ChargeDataLakeListener(
    private val dataLakeClient: DataLakeClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: ChargeCompletedEvent) {
        runCatching {
            dataLakeClient.publish(ChargeEvent(event.chargeId, event.userId, event.amount, event.balance))
        }.onFailure { ex ->
            log.warn("[EDA] 데이터레이크 적재 실패 chargeId=${event.chargeId} reason=${ex.message}")
        }
    }
}
