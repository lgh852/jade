package co.kr.ghlee.jade.api.jadehr.infrastructure.config

import io.netty.channel.ChannelOption
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.ReadTimeoutHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.FileInputStream
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory

@Configuration
@EnableConfigurationProperties(JadeHrProperties::class)
class WebClientConfig {

    @Bean
    fun jadeHrWebClient(
        builder: WebClient.Builder,
        properties: JadeHrProperties,
    ): WebClient {
        val httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.connectTimeout.toMillis().toInt())
            .responseTimeout(properties.responseTimeout)
            .doOnConnected { connection ->
                connection.addHandlerLast(
                    ReadTimeoutHandler(properties.responseTimeout.toSeconds(), TimeUnit.SECONDS)
                )
            }
            .let { client ->
                if (properties.trustStorePath.isNullOrBlank()) {
                    client
                } else {
                    val sslContext = SslContextBuilder.forClient()
                        .trustManager(trustManagerFactory(properties))
                        .build()

                    client.secure { it.sslContext(sslContext) }
                }
            }

        val strategies = ExchangeStrategies.builder()
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
            .build()

        return builder
            .baseUrl(properties.baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .exchangeStrategies(strategies)
            .defaultHeader("User-Agent", "Mozilla/5.0 JadeHR Calendar Sync")
            .build()
    }

    private fun trustManagerFactory(properties: JadeHrProperties): TrustManagerFactory {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(properties.trustStorePath).use { input ->
            keyStore.load(input, properties.trustStorePassword?.toCharArray())
        }

        return TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
    }
}
