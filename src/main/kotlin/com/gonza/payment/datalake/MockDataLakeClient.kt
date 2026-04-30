package com.gonza.payment.datalake

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class MockDataLakeClient : DataLakeClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun publish(event: ChargeEvent) {
        log.info("[시나리오6][DataLake] 충전 이벤트 적재 chargeId=${event.chargeId} userId=${event.userId} amount=${event.amount}")
    }
}
