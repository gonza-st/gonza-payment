package com.gonza.payment.concurrency

import com.gonza.payment.domain.ChargeStatus
import com.gonza.payment.domain.User
import com.gonza.payment.domain.Wallet
import com.gonza.payment.repository.ChargeRepository
import com.gonza.payment.repository.PointLedgerRepository
import com.gonza.payment.repository.UserRepository
import com.gonza.payment.repository.WalletRepository
import com.gonza.payment.service.ChargeService
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
class ChargeConcurrencyTest {

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

    @Autowired lateinit var chargeService: ChargeService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var walletRepository: WalletRepository
    @Autowired lateinit var chargeRepository: ChargeRepository
    @Autowired lateinit var pointLedgerRepository: PointLedgerRepository

    private lateinit var userId: UUID

    @BeforeEach
    fun setUp() {
        pointLedgerRepository.deleteAll()
        chargeRepository.deleteAll()
        walletRepository.deleteAll()
        userRepository.deleteAll()

        val user = User(name = "ChargeConcurrencyUser", phoneNumber = "010-0000-0000")
        userRepository.save(user)
        userId = user.id
        walletRepository.save(Wallet(userId = userId, balance = 0L))
    }

    @Test
    fun `concurrent charges with same idempotency key - balance increases exactly once`() {
        val threadCount = 5
        val latch = CountDownLatch(threadCount)
        val executor = Executors.newFixedThreadPool(threadCount)
        val successCount = AtomicInteger(0)
        val conflictCount = AtomicInteger(0)
        val errorCount = AtomicInteger(0)
        val idempotencyKey = "idem-${UUID.randomUUID()}"
        val amount = 10_000L

        repeat(threadCount) {
            executor.submit {
                try {
                    chargeService.chargePoints(userId, amount, idempotencyKey)
                    successCount.incrementAndGet()
                } catch (e: Exception) {
                    if (e.message?.contains("processed") == true ||
                        e.message?.contains("failed") == true
                    ) {
                        conflictCount.incrementAndGet()
                    } else {
                        errorCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        latch.await()
        executor.shutdown()

        // 500 에러(미처리 예외) 없음
        assertThat(errorCount.get()).isEqualTo(0)
        // 성공(200) + 충돌(409)만 발생, 성공은 최소 1건
        assertThat(successCount.get()).isGreaterThanOrEqualTo(1)
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(threadCount)

        // 핵심: 잔액은 정확히 1회만 증가
        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(amount)

        // DB에 charge 레코드 1건만 존재
        val charges = chargeRepository.findAll()
        assertThat(charges).hasSize(1)
        assertThat(charges[0].status).isEqualTo(ChargeStatus.COMPLETED)
    }

    @Test
    fun `completed charge with same key returns idempotent response`() {
        val idempotencyKey = "idem-${UUID.randomUUID()}"
        val amount = 5_000L

        val firstResult = chargeService.chargePoints(userId, amount, idempotencyKey)
        assertThat(firstResult.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(firstResult.balance).isEqualTo(amount)

        val secondResult = chargeService.chargePoints(userId, amount, idempotencyKey)
        assertThat(secondResult.chargeId).isEqualTo(firstResult.chargeId)
        assertThat(secondResult.status).isEqualTo(ChargeStatus.COMPLETED)
        assertThat(secondResult.balance).isEqualTo(amount)

        val wallet = walletRepository.findById(userId).get()
        assertThat(wallet.balance).isEqualTo(amount)
    }
}
