package com.gonza.payment.dto

import java.util.UUID

data class CreateUserRequest(val name: String, val phoneNumber: String, val email: String)
data class CreateUserResponse(val userId: UUID)
data class WalletResponse(val balance: Long)
