package co.kr.ghlee.jade.api.jadehr.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "telegram")
data class TelegramNotificationProperties(
    val botToken: String = "",
    val chatId: String = "",
)
