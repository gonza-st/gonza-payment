package com.gonza.payment.repository

import com.gonza.payment.domain.Gifticon
import com.gonza.payment.domain.GifticonProduct
import com.gonza.payment.domain.GifticonStatus
import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

@DataJpaTest
@ActiveProfiles("test")
class GifticonRepositoryTest {

    @Autowired lateinit var gifticonRepository: GifticonRepository
    @Autowired lateinit var gifticonProductRepository: GifticonProductRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository

    private lateinit var userId: UUID
    private lateinit var productId: UUID

    @BeforeEach
    fun setUp() {
        val user = User(name = "TestUser", phoneNumber = "010-0000-0000")
        userRepository.save(user)
        userId = user.id
        walletRepository.save(Wallet(userId = userId, balance = 10000L))

        val product = GifticonProduct(name = "Coffee", price = 4500L)
        gifticonProductRepository.save(product)
        productId = product.id
    }

    @Test
    fun `consumeById - transitions ISSUED to CONSUMED`() {
        val gifticon = Gifticon(userId = userId, productId = productId, code = "CODE001")
        gifticonRepository.saveAndFlush(gifticon)

        val updated = gifticonRepository.consumeById(gifticon.id, Instant.now())

        assertThat(updated).isEqualTo(1)
    }

    @Test
    fun `consumeById - already consumed returns 0`() {
        val gifticon = Gifticon(
            userId = userId,
            productId = productId,
            code = "CODE002",
            status = GifticonStatus.CONSUMED
        )
        gifticonRepository.saveAndFlush(gifticon)

        val updated = gifticonRepository.consumeById(gifticon.id, Instant.now())

        assertThat(updated).isEqualTo(0)
    }
}
