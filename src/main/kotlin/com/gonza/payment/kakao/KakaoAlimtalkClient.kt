package com.gonza.payment.kakao

interface KakaoAlimtalkClient {
    fun send(phoneNumber: String, title: String, content: String): KakaoSendResult
}

data class KakaoSendResult(val success: Boolean, val messageId: String? = null, val errorCode: String? = null)
