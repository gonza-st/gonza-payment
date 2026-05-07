package com.gonza.payment.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {

    @Bean(name = ["applicationTaskExecutor"])
    fun applicationTaskExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 8
            maxPoolSize = 32
            queueCapacity = 200
            setThreadNamePrefix("eda-")
            initialize()
        }
}
