package com.gonza.payment.email

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockEmailClient(
    @Value("\${email.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${email.mock.delay-ms:0}") private val delayMs: Long
) : EmailClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(email: String, title: String, content: String): EmailSendResult {
        if (delayMs > 0) {
            Thread.sleep(delayMs)
        }

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK EMAIL] FAILED to={}, title={}", email, title)
            return EmailSendResult(
                success = false,
                errorCode = "EMAIL_SEND_FAILED"
            )
        }

        log.info("[MOCK EMAIL] SENT to={}, title={}, content={}", email, title, content)
        return EmailSendResult(
            success = true,
            messageId = "email-${UUID.randomUUID()}"
        )
    }
}
