package co.kr.ghlee.jade.api.auth.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jade.auth")
data class JadeAuthProperties(
    val cryptoSecret: String = "local-dev-only-change-me",
    /** 화면 로그인 유지 기간 */
    val sessionTtl: Duration = Duration.ofDays(30),
)
