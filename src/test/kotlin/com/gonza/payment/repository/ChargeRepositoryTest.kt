package com.gonza.payment.repository

import com.gonza.payment.domain.Charge
import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ActiveProfiles
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
class ChargeRepositoryTest {

    @Autowired lateinit var chargeRepository: ChargeRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        val user = User(name = "TestUser")
        userRepository.save(user)
        userId = user.id
        walletRepository.save(Wallet(userId = userId))
    }

    @Test
    fun `findByUserIdAndIdempotencyKey - returns charge when exists`() {
        val charge = Charge(userId = userId, idempotencyKey = "key-1", amount = 5000L)
        chargeRepository.save(charge)

        val found = chargeRepository.findByUserIdAndIdempotencyKey(userId, "key-1")

        assertThat(found).isNotNull
        assertThat(found!!.amount).isEqualTo(5000L)
    }

    @Test
    fun `findByUserIdAndIdempotencyKey - returns null when not exists`() {
        val found = chargeRepository.findByUserIdAndIdempotencyKey(userId, "non-existent")
        assertThat(found).isNull()
    }

    @Test
    fun `unique constraint - duplicate userId + idempotencyKey throws`() {
        val charge1 = Charge(userId = userId, idempotencyKey = "dup-key", amount = 5000L)
        chargeRepository.saveAndFlush(charge1)

        val charge2 = Charge(userId = userId, idempotencyKey = "dup-key", amount = 3000L)

        assertThatThrownBy {
            chargeRepository.saveAndFlush(charge2)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `same idempotencyKey for different users is allowed`() {
        val user2 = User(name = "User2")
        userRepository.save(user2)

        chargeRepository.save(Charge(userId = userId, idempotencyKey = "shared-key", amount = 5000L))
        chargeRepository.save(Charge(userId = user2.id, idempotencyKey = "shared-key", amount = 3000L))

        assertThat(chargeRepository.findByUserIdAndIdempotencyKey(userId, "shared-key")).isNotNull
        assertThat(chargeRepository.findByUserIdAndIdempotencyKey(user2.id, "shared-key")).isNotNull
    }
}
