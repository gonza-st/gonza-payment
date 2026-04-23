package com.gonza.payment.push

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockPushClient(
    @Value("\${push.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${push.mock.delay-ms:0}") private val delayMs: Long
) : PushClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(deviceToken: String, title: String, content: String): PushSendResult {
        if (delayMs > 0) Thread.sleep(delayMs)

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK PUSH] FAILED to={}, title={}", deviceToken, title)
            return PushSendResult(success = false, errorCode = "PUSH_SEND_FAILED")
        }

        log.info("[MOCK PUSH] SENT to={}, title={}", deviceToken, title)
        return PushSendResult(success = true, messageId = "push-${UUID.randomUUID()}")
    }
}
