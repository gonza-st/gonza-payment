package com.gonza.payment.slack

interface SlackClient {
    fun send(channel: String, title: String, content: String): SlackSendResult
}

data class SlackSendResult(val success: Boolean, val messageId: String? = null, val errorCode: String? = null)
