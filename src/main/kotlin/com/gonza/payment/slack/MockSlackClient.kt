package com.gonza.payment.slack

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockSlackClient(
    @Value("\${slack.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${slack.mock.delay-ms:0}") private val delayMs: Long
) : SlackClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(channel: String, title: String, content: String): SlackSendResult {
        if (delayMs > 0) Thread.sleep(delayMs)

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK SLACK] FAILED channel={}, title={}", channel, title)
            return SlackSendResult(success = false, errorCode = "SLACK_SEND_FAILED")
        }

        log.info("[MOCK SLACK] SENT channel={}, title={}", channel, title)
        return SlackSendResult(success = true, messageId = "slack-${UUID.randomUUID()}")
    }
}
