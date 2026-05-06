package com.gonza.payment.notification

import com.gonza.payment.event.ChargeCompletedEvent
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class ChargeNotificationListener(
    private val router: NotificationRouter,
    private val dispatcher: ChannelDispatcher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun on(event: ChargeCompletedEvent) {
        val channels = router.route(event.userId)
        log.info("[EDA] 알림 라우팅 결과 userId=${event.userId} channels=$channels chargeId=${event.chargeId}")
        val title = "포인트 충전 완료"
        val content = "${event.amount}P가 충전되었습니다. 현재 잔액 ${event.balance}P"
        channels.forEach { channel ->
            dispatcher.send(event.userId, title, content, channel, event.chargeId)
        }
    }
}
