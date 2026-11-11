package co.kr.ghlee.jade.common.security

import co.kr.ghlee.jade.api.auth.application.JwtTokenService
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.Optional

/**
 * Authorization: Bearer 헤더나 JADE_SESSION 쿠키의 JWT 를 검증해 로그인 사용자로 세운다.
 * 토큰이 없거나 잘못됐으면 익명으로 넘기고, 막을지는 SecurityConfig 의 접근 규칙이 정한다.
 * 쿠키 토큰은 발급 하루가 지나면 새로 발급해 유효기간(30일)을 다시 채운다.
 */
class JwtAuthenticationWebFilter(
    private val tokens: JwtTokenService,
) : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val cookieToken = exchange.request.cookies.getFirst(JwtTokenService.COOKIE_NAME)?.value
        val token = bearerToken(exchange) ?: cookieToken
        if (token.isNullOrBlank()) return chain.filter(exchange)

        return tokens.decode(token)
            .map { jwt ->
                // 쿠키로 쓰는 중이면 유효기간을 계속 늘려 준다 (Bearer 호출은 그대로)
                if (token == cookieToken && tokens.needsRenewal(jwt)) {
                    exchange.response.addCookie(tokens.cookie(tokens.issue(jwt.subject)))
                }
                Optional.of<Authentication>(UsernamePasswordAuthenticationToken.authenticated(jwt.subject, null, AUTHORITIES))
            }
            .onErrorResume { Mono.just(Optional.empty()) }
            .flatMap { auth ->
                if (auth.isPresent) {
                    chain.filter(exchange).contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth.get()))
                } else {
                    chain.filter(exchange)
                }
            }
    }

    private fun bearerToken(exchange: ServerWebExchange): String? =
        exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()

    companion object {
        private val AUTHORITIES = listOf(SimpleGrantedAuthority("ROLE_USER"))
    }
}
