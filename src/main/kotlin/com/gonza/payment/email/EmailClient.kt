package com.gonza.payment.email

interface EmailClient {
    fun send(email: String, title: String, content: String): EmailSendResult
}

data class EmailSendResult(
    val success: Boolean,
    val messageId: String? = null,
    val errorCode: String? = null
)
