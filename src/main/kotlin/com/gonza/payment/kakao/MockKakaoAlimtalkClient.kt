package com.gonza.payment.kakao

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID
import kotlin.random.Random

@Component
class MockKakaoAlimtalkClient(
    @Value("\${kakao.mock.fail-rate:0.0}") private val failRate: Double,
    @Value("\${kakao.mock.delay-ms:0}") private val delayMs: Long
) : KakaoAlimtalkClient {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(phoneNumber: String, title: String, content: String): KakaoSendResult {
        if (delayMs > 0) Thread.sleep(delayMs)

        if (Random.nextDouble() < failRate) {
            log.warn("[MOCK KAKAO] FAILED to={}, title={}", phoneNumber, title)
            return KakaoSendResult(success = false, errorCode = "KAKAO_SEND_FAILED")
        }

        log.info("[MOCK KAKAO] SENT to={}, title={}", phoneNumber, title)
        return KakaoSendResult(success = true, messageId = "kakao-${UUID.randomUUID()}")
    }
}
