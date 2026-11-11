package co.kr.ghlee.jade.common.security

import co.kr.ghlee.jade.api.auth.application.JwtTokenService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.authorization.AuthorizationDecision
import org.springframework.security.authorization.ReactiveAuthorizationManager
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.Authentication
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authorization.AuthorizationContext
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import reactor.core.publisher.Mono
import java.net.URI
import java.net.URLEncoder

/**
 * - 로그인 화면·로그인 API·헬스체크는 열어 둔다.
 * - 출퇴근 기록 API(/api/jadehr/attendance-records 아래)는 단축어가 인증키·비밀번호를 직접 보내므로 JWT 없이 연다.
 * - 나머지 API 는 JWT 필수, 쿼리의 id/userId 는 로그인한 본인 아이디여야 한다.
 * - 화면은 로그인 전이면 로그인 화면으로 보낸다. API 는 401/403 JSON.
 * 쿠키는 SameSite=Lax 라 다른 사이트에서 보낸 POST 에는 실리지 않아 CSRF 토큰은 쓰지 않는다.
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity, tokens: JwtTokenService): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
            .addFilterAt(JwtAuthenticationWebFilter(tokens), SecurityWebFiltersOrder.AUTHENTICATION)
            .authorizeExchange {
                it.pathMatchers("/login.html", "/sidebar.js", "/favicon.ico", "/actuator/health", "/actuator/health/**").permitAll()
                it.pathMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout").permitAll()
                it.pathMatchers("/api/jadehr/attendance-records/**").permitAll()
                it.pathMatchers("/api/**").access(OwnerAuthorizationManager)
                it.anyExchange().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { exchange, _ ->
                    val request = exchange.request
                    if (request.path.pathWithinApplication().value().startsWith("/api/")) {
                        exchange.response.writeJson(HttpStatus.UNAUTHORIZED, "LOGIN_REQUIRED", "로그인이 필요합니다.")
                    } else {
                        val target = request.path.value() + (request.uri.rawQuery?.let { q -> "?$q" } ?: "")
                        exchange.response.statusCode = HttpStatus.FOUND
                        exchange.response.headers.location = URI.create("/login.html?next=" + URLEncoder.encode(target, Charsets.UTF_8))
                        exchange.response.setComplete()
                    }
                }
                it.accessDeniedHandler { exchange, _ ->
                    exchange.response.writeJson(HttpStatus.FORBIDDEN, "FORBIDDEN", "로그인한 본인 정보만 볼 수 있습니다.")
                }
            }
            .build()

    /** 로그인했고, 쿼리에 id/userId 가 있으면 본인 아이디일 때만 허용 */
    private object OwnerAuthorizationManager : ReactiveAuthorizationManager<AuthorizationContext> {
        @Deprecated("Spring Security 6.4+ 는 authorize 를 쓰지만 check 를 구현해야 한다")
        override fun check(authentication: Mono<Authentication>, context: AuthorizationContext): Mono<AuthorizationDecision> =
            authentication
                .filter { it.isAuthenticated && it !is AnonymousAuthenticationToken }
                .map { auth ->
                    val query = context.exchange.request.queryParams
                    val requested = listOfNotNull(query.getFirst("id"), query.getFirst("userId")).map { it.trim() }
                    AuthorizationDecision(requested.all { it.equals(auth.name, ignoreCase = true) })
                }
                .defaultIfEmpty(AuthorizationDecision(false))
    }

    private fun ServerHttpResponse.writeJson(status: HttpStatus, code: String, message: String): Mono<Void> {
        statusCode = status
        headers.contentType = MediaType.APPLICATION_JSON
        val body = """{"code":"$code","message":"$message"}""".toByteArray(Charsets.UTF_8)
        return writeWith(Mono.just(bufferFactory().wrap(body)))
    }
}
