package com.gonza.payment.concurrency

import com.gonza.payment.domain.GifticonProduct
import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import com.gonza.payment.repository.GifticonProductRepository
import com.gonza.payment.repository.GifticonRepository
import com.gonza.payment.repository.PurchaseRequestRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.repository.WalletRepository
import com.gonza.payment.service.GifticonService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest
@Testcontainers
@Tag("docker")
class PurchaseConcurrencyTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16").apply {
            withDatabaseName("points")
            withUsername("points")
            withPassword("points")
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("pg.mock.fail-rate") { "0.0" }
            registry.add("pg.mock.delay-ms") { "0" }
        }
    }

    @Autowired lateinit var gifticonService: GifticonService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var gifticonProductRepository: GifticonProductRepository
    @Autowired lateinit var gifticonRepository: GifticonRepository
    @Autowired lateinit var purchaseRequestRepository: PurchaseRequestRepository

    private lateinit var userId: UUID
    private lateinit var productId: UUID

    @BeforeEach
    fun setUp() {
        purchaseRequestRepository.deleteAll()
        gifticonRepository.deleteAll()
        walletRepository.deleteAll()
        userRepository.deleteAll()
        gifticonProductRepository.deleteAll()

        val user = User(name = "ConcurrencyUser", phoneNumber = "010-0000-0000")
        userRepository.save(user)
        userId = user.id

        walletRepository.save(Wallet(userId = userId, balance = 5000L))

        val product = GifticonProduct(name = "Coffee", price = 4500L)
        gifticonProductRepository.save(product)
        productId = product.id
    }

    @Test
    fun `concurrent purchases - only one succeeds when balance is insufficient for both`() {
        val threadCount = 2
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val failCount = AtomicInteger(0)

        repeat(threadCount) { index ->
            executor.submit {
                try {
                    gifticonService.purchaseGifticon(userId, productId, "purchase-key-$index")
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    failCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        assertThat(successCount.get()).isEqualTo(1)
        assertThat(failCount.get()).isEqualTo(1)

        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(500L)
    }
}
