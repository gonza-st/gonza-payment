package com.gonza.payment.repository

import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
class WalletRepositoryTest {

    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var userRepository: UserRepository

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        val user = User(name = "TestUser", phoneNumber = "010-0000-0000")
        userRepository.save(user)
        userId = user.id
        walletRepository.save(Wallet(userId = userId, balance = 10000L))
    }

    @Test
    fun `addBalance - increases wallet balance`() {
        val updated = walletRepository.addBalance(userId, 5000L)

        assertThat(updated).isEqualTo(1)
        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(15000L)
    }

    @Test
    fun `subtractBalance - decreases wallet balance when sufficient`() {
        val updated = walletRepository.subtractBalance(userId, 3000L)

        assertThat(updated).isEqualTo(1)
        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(7000L)
    }

    @Test
    fun `subtractBalance - returns 0 when insufficient balance`() {
        val updated = walletRepository.subtractBalance(userId, 15000L)

        assertThat(updated).isEqualTo(0)
        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(10000L)
    }

    @Test
    fun `subtractBalance - exact balance succeeds`() {
        val updated = walletRepository.subtractBalance(userId, 10000L)

        assertThat(updated).isEqualTo(1)
        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(0L)
    }
}
