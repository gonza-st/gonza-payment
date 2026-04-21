package com.gonza.payment.service

import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import com.gonza.payment.dto.CreateUserRequest
import com.gonza.payment.dto.CreateUserResponse
import com.gonza.payment.dto.WalletResponse
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.repository.WalletRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val walletRepository: WalletRepository
) {

    @Transactional
    fun createUser(request: CreateUserRequest): CreateUserResponse {
        val user = User(name = request.name, phoneNumber = request.phoneNumber, email = request.email)
        userRepository.save(user)
        walletRepository.save(Wallet(userId = user.id))
        return CreateUserResponse(userId = user.id)
    }

    @Transactional(readOnly = true)
    fun getWallet(userId: UUID): WalletResponse {
        val wallet = walletRepository.findById(userId)
            .orElseThrow { NotFoundException("Wallet not found for user $userId") }
        return WalletResponse(balance = wallet.balance)
    }
}
