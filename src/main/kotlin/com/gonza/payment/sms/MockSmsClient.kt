package com.gonza.payment.sms

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockSmsClient(
    @Value("\${sms.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${sms.mock.delay-ms:0}") private val delayMs: Long
) : SmsClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(phoneNumber: String, title: String, content: String): SmsSendResult {
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK SMS] FAILED to={}, title={}", phoneNumber, title)
            return SmsSendResult(
                success = false,
                errorCode = "SMS_SEND_FAILED"
            )
        }

        log.info("[MOCK SMS] SENT to={}, title={}, content={}", phoneNumber, title, content)
        return SmsSendResult(
            success = true,
            messageId = "sms-${UUID.randomUUID()}"
        )
    }
}
