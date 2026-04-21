package com.gonza.payment.sms

interface SmsClient {
    fun send(phoneNumber: String, title: String, content: String): SmsSendResult
}

data class SmsSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val errorCode: String? = null
)
