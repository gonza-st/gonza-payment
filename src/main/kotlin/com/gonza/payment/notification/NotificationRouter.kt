package com.gonza.payment.notification

import com.gonza.payment.domain.NotificationChannel
import com.gonza.payment.domain.User
import com.gonza.payment.exception.NotFoundException
import com.gonza.payment.repository.UserRepository
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalTime
import java.util.UUID

@Component
class NotificationRouter(
    private val userRepository: UserRepository,
    private val clock: Clock = Clock.systemDefaultZone()
) {
    fun route(userId: UUID): List<NotificationChannel> {
        val user = userRepository.findById(userId)
            .orElseThrow { NotFoundException("User not found: $userId") }
        val now = LocalTime.now(clock)
        val isNight = !now.isBefore(NIGHT_START) || now.isBefore(NIGHT_END)

        val channels = mutableListOf<NotificationChannel>()
        if (!user.isForeignNumber()) {
            channels += if (isNight) NotificationChannel.PUSH else NotificationChannel.SMS
        }
        channels += NotificationChannel.EMAIL
        if (user.vip) channels += NotificationChannel.KAKAO_ALIMTALK
        if (user.marketingOptIn) channels += NotificationChannel.SLACK
        return channels
    }

    private fun User.isForeignNumber(): Boolean {
        val digits = phoneNumber.filter { it.isDigit() }
        return !digits.startsWith("010") && !digits.startsWith("8210")
    }

    companion object {
        private val NIGHT_START: LocalTime = LocalTime.of(22, 0)
        private val NIGHT_END: LocalTime = LocalTime.of(8, 0)
    }
}
