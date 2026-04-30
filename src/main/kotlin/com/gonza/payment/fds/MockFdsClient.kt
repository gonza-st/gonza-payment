package com.gonza.payment.fds

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class MockFdsClient : FdsClient {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(userId: UUID, amount: Long): FdsResult {
        log.info("[시나리오6][FDS] 검사 userId=$userId amount=$amount")
        return FdsResult(approved = true)
    }
}
