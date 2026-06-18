package co.kr.ghlee.jade.api.auth.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jade.auth")
data class JadeAuthProperties(
    val cryptoSecret: String = "local-dev-only-change-me",
)
