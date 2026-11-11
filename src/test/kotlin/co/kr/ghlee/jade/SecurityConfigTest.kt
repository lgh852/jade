package co.kr.ghlee.jade

import co.kr.ghlee.jade.api.auth.application.CredentialEncryptor
import co.kr.ghlee.jade.api.auth.application.JwtTokenService
import co.kr.ghlee.jade.api.auth.domain.JadeAuthUser
import co.kr.ghlee.jade.api.auth.infrastructure.JadeAuthUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant

@SpringBootTest
@AutoConfigureWebTestClient
class SecurityConfigTest {

    @Autowired
    lateinit var client: WebTestClient

    @Autowired
    lateinit var tokens: JwtTokenService

    @Autowired
    lateinit var users: JadeAuthUserRepository

    @Autowired
    lateinit var encryptor: CredentialEncryptor

    private fun cookie(userId: String, now: Instant = Instant.now()) = tokens.issue(userId, now)

    @Test
    fun `로그인 전 캘린더 화면은 로그인 화면으로 보낸다`() {
        client.get().uri("/calendar.html?year=2026&month=11").exchange()
            .expectStatus().isFound
            .expectHeader().valueEquals("Location", "/login.html?next=%2Fcalendar.html%3Fyear%3D2026%26month%3D11")
    }

    @Test
    fun `로그인하면 캘린더 화면이 열린다`() {
        client.get().uri("/calendar.html").cookie(JwtTokenService.COOKIE_NAME, cookie("lgh852")).exchange()
            .expectStatus().isOk
    }

    @Test
    fun `로그인 화면과 헬스체크는 열려 있다`() {
        client.get().uri("/login.html").exchange().expectStatus().isOk
        client.get().uri("/sidebar.js").exchange().expectStatus().isOk
        client.get().uri("/actuator/health").exchange().expectStatus().isOk
    }

    @Test
    fun `로그인 전 API 는 401`() {
        client.get().uri("/api/jadehr/work-plan?id=lgh852").exchange()
            .expectStatus().isUnauthorized
            .expectBody().jsonPath("$.code").isEqualTo("LOGIN_REQUIRED")
        client.get().uri("/api/auth/me").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `다른 사람 아이디로 부르면 403`() {
        client.get().uri("/api/jadehr/work-plan?id=other01").cookie(JwtTokenService.COOKIE_NAME, cookie("lgh852")).exchange()
            .expectStatus().isForbidden
        client.get().uri("/api/jadehr/attendance-calendar?userId=other01&year=2026&month=11")
            .header("Authorization", "Bearer " + cookie("lgh852")).exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `만료되거나 위조된 토큰은 401`() {
        val expired = cookie("lgh852", Instant.now().minus(Duration.ofDays(31)))
        client.get().uri("/api/jadehr/work-plan?id=lgh852").cookie(JwtTokenService.COOKIE_NAME, expired).exchange()
            .expectStatus().isUnauthorized
        val parts = cookie("lgh852").split('.')
        val forged = listOf(parts[0], "eyJzdWIiOiJqaGtpbTAwIiwiaXNzIjoiamFkZSJ9", parts[2]).joinToString(".")
        client.get().uri("/api/jadehr/work-plan?id=other01").cookie(JwtTokenService.COOKIE_NAME, forged).exchange()
            .expectStatus().isUnauthorized
    }

    @Test
    fun `단축어용 출퇴근 API 는 JWT 없이 통과해 인증키 검사로 간다`() {
        client.get().uri("/api/jadehr/attendance-records/start/auto").exchange()
            .expectStatus().isUnauthorized
            .expectBody().jsonPath("$.code").isEqualTo("JADE_AUTH_REQUIRED")
    }

    @Test
    fun `토큰이 있으면 me 가 사용자 정보를 돌려준다`() {
        if (users.findAllByJadeHrUserIdAndActiveTrueOrderByIdAsc("metest").isEmpty()) {
            users.save(JadeAuthUser(authKey = "metest", nickname = "테스트", companyCode = "2202010", jadeHrUserId = "metest", jadeHrPasswordEnc = encryptor.encrypt("x")))
        }
        client.get().uri("/api/auth/me").cookie(JwtTokenService.COOKIE_NAME, cookie("metest")).exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.id").isEqualTo("metest")
    }

    private fun ensureUser(id: String) {
        if (users.findAllByJadeHrUserIdAndActiveTrueOrderByIdAsc(id).isEmpty()) {
            users.save(JadeAuthUser(authKey = id, nickname = id, companyCode = "2202010", jadeHrUserId = id, jadeHrPasswordEnc = encryptor.encrypt("x")))
        }
    }

    @Test
    fun `하루 지난 쿠키로 접속하면 새 쿠키를 내려 유효기간을 늘린다`() {
        ensureUser("renew")
        val old = cookie("renew", Instant.now().minus(Duration.ofDays(2)))
        client.get().uri("/api/auth/me").cookie(JwtTokenService.COOKIE_NAME, old).exchange()
            .expectStatus().isOk
            .expectCookie().exists(JwtTokenService.COOKIE_NAME)
            .expectCookie().maxAge(JwtTokenService.COOKIE_NAME, Duration.ofDays(30))
    }

    @Test
    fun `발급 직후 쿠키는 다시 내려주지 않는다`() {
        ensureUser("renew")
        client.get().uri("/api/auth/me").cookie(JwtTokenService.COOKIE_NAME, cookie("renew")).exchange()
            .expectStatus().isOk
            .expectCookie().doesNotExist(JwtTokenService.COOKIE_NAME)
    }

    @Test
    fun `쿠키가 없어도 저장해 둔 토큰으로 refresh 하면 쿠키와 새 토큰을 받는다`() {
        ensureUser("renew")
        client.post().uri("/api/auth/refresh").header("Authorization", "Bearer " + cookie("renew")).exchange()
            .expectStatus().isOk
            .expectCookie().exists(JwtTokenService.COOKIE_NAME)
            .expectBody().jsonPath("$.id").isEqualTo("renew").jsonPath("$.token").isNotEmpty
    }

    @Test
    fun `만료된 토큰으로는 refresh 할 수 없다`() {
        client.post().uri("/api/auth/refresh").header("Authorization", "Bearer " + cookie("renew", Instant.now().minus(Duration.ofDays(31)))).exchange()
            .expectStatus().isUnauthorized
    }
}
