package com.gonza.payment.push

interface PushClient {
    fun send(deviceToken: String, title: String, content: String): PushSendResult
}

data class PushSendResult(val success: Boolean, val messageId: String? = null, val errorCode: String? = null)
