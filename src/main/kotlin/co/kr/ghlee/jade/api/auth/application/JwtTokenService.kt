package co.kr.ghlee.jade.api.auth.application

import co.kr.ghlee.jade.api.auth.infrastructure.config.JadeAuthProperties
import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.http.ResponseCookie
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/**
 * 화면 로그인용 JWT(HS256). subject = JadeHR 아이디.
 * 서버에 세션을 두지 않아 재배포해도 로그인이 유지된다. 서명 키는 jade.auth.crypto-secret 에서 만든다.
 */
@Component
class JwtTokenService(
    private val properties: JadeAuthProperties,
) {
    private val key = SecretKeySpec(
        MessageDigest.getInstance("SHA-256").digest("jwt:${properties.cryptoSecret}".toByteArray(Charsets.UTF_8)),
        "HmacSHA256",
    )
    private val encoder = NimbusJwtEncoder(ImmutableSecret(key))
    private val decoder = NimbusReactiveJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build()
        .apply { setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER)) }

    val ttl: Duration get() = properties.sessionTtl

    fun issue(userId: String, now: Instant = Instant.now()): String {
        val claims = JwtClaimsSet.builder()
            .issuer(ISSUER)
            .subject(userId)
            .issuedAt(now)
            .expiresAt(now.plus(ttl))
            .build()
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).tokenValue
    }

    /** 발급한 지 하루가 지난 토큰이면 새로 발급해 유효기간을 다시 늘린다 (쓰는 동안은 만료되지 않게) */
    fun needsRenewal(jwt: Jwt, now: Instant = Instant.now()): Boolean =
        jwt.issuedAt?.isBefore(now.minus(RENEW_AFTER)) ?: true

    fun cookie(token: String): ResponseCookie = cookie(token, ttl)

    fun expiredCookie(): ResponseCookie = cookie("", Duration.ZERO)

    private fun cookie(value: String, maxAge: Duration): ResponseCookie =
        ResponseCookie.from(COOKIE_NAME, value)
            .httpOnly(true)
            .sameSite("Lax")
            .path("/")
            .maxAge(maxAge)
            .build()

    /** 서명·만료·발급자를 검사한다. 잘못된 토큰이면 에러 Mono */
    fun decode(token: String): Mono<Jwt> = decoder.decode(token)

    companion object {
        const val COOKIE_NAME = "JADE_SESSION"
        const val ISSUER = "jade"
        private val RENEW_AFTER: Duration = Duration.ofDays(1)
    }
}
