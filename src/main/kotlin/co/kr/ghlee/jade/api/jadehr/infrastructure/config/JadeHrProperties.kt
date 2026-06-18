package co.kr.ghlee.jade.api.jadehr.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "jadehr")
data class JadeHrProperties(
    val baseUrl: String = "https://ehr.jadehr.co.kr",
    val defaultCompanyCode: String = "2202010",
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val responseTimeout: Duration = Duration.ofSeconds(20),
    val trustStorePath: String? = null,
    val trustStorePassword: String? = null,
)
