package com.gonza.payment.service

import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import com.gonza.payment.dto.CreateUserRequest
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.repository.WalletRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class UserServiceTest {

    @Mock lateinit var userRepository: UserRepository
    @Mock lateinit var walletRepository: WalletRepository

    private lateinit var userService: UserService

    @BeforeEach
    fun setUp() {
        userService = UserService(userRepository, walletRepository)
    }

    @Test
    fun `createUser - creates user and wallet`() {
        whenever(userRepository.save(any<User>())).thenAnswer { it.arguments[0] }
        whenever(walletRepository.save(any<Wallet>())).thenAnswer { it.arguments[0] }

        val result = userService.createUser(CreateUserRequest(name = "TestUser"))

        assertThat(result.userId).isNotNull()
    }

    @Test
    fun `getWallet - returns balance`() {
        val userId = UUID.randomUUID()
        val wallet = Wallet(userId = userId, balance = 10000L)

        whenever(walletRepository.findById(userId)).thenReturn(Optional.of(wallet))

        val result = userService.getWallet(userId)

        assertThat(result.balance).isEqualTo(10000L)
    }

    @Test
    fun `getWallet - throws not found for unknown user`() {
        val userId = UUID.randomUUID()

        whenever(walletRepository.findById(userId)).thenReturn(Optional.empty())

        assertThatThrownBy {
            userService.getWallet(userId)
        }.isInstanceOf(NotFoundException::class.java)
    }
}
