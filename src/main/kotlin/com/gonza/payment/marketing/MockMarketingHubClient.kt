package com.gonza.payment.marketing

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockMarketingHubClient(
    @Value("\${marketing-hub.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${marketing-hub.mock.delay-ms:0}") private val delayMs: Long
) : MarketingHubClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(userId: String, title: String, content: String): MarketingHubSendResult {
        if (delayMs > 0) Thread.sleep(delayMs)

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK MARKETING HUB] FAILED userId={}, title={}", userId, title)
            return MarketingHubSendResult(success = false, errorCode = "MARKETING_HUB_SEND_FAILED")
        }

        log.info("[MOCK MARKETING HUB] SENT userId={}, title={}", userId, title)
        return MarketingHubSendResult(success = true, messageId = "mkt-${UUID.randomUUID()}")
    }
}
