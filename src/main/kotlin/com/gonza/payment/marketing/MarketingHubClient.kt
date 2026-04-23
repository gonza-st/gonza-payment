package com.gonza.payment.marketing

interface MarketingHubClient {
    fun send(userId: String, title: String, content: String): MarketingHubSendResult
}

data class MarketingHubSendResult(val success: Boolean, val messageId: String? = null, val errorCode: String? = null)
