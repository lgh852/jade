package co.kr.ghlee.jade.api.auth.presentation

import co.kr.ghlee.jade.api.auth.application.JwtTokenService
import co.kr.ghlee.jade.api.auth.application.LoginService
import co.kr.ghlee.jade.api.auth.application.LoginUser
import co.kr.ghlee.jade.api.auth.exception.JadeAuthRequiredException
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/auth")
class LoginController(
    private val loginService: LoginService,
    private val tokens: JwtTokenService,
) {

    /**
     * JadeHR 로그인에 성공하면 JWT 를 HttpOnly 쿠키로 내려주고, 본문에도 담아 준다.
     * 본문 토큰은 화면이 브라우저 저장소에 보관했다가 쿠키가 지워졌을 때 /refresh 로 다시 쿠키를 받는 데 쓴다.
     */
    @PostMapping("/login")
    suspend fun login(@Valid @RequestBody request: LoginRequest, response: ServerHttpResponse): LoginResponse {
        val user = loginService.login(request.id, request.password)
        return issue(user, response)
    }

    /** 유효한 토큰(Bearer 또는 쿠키)으로 새 토큰·쿠키를 받는다 */
    @PostMapping("/refresh")
    suspend fun refresh(principal: Principal, response: ServerHttpResponse): LoginResponse {
        val user = loginService.find(principal.name) ?: throw JadeAuthRequiredException("로그인이 필요합니다.")
        return issue(user, response)
    }

    @PostMapping("/logout")
    fun logout(response: ServerHttpResponse) {
        response.addCookie(tokens.expiredCookie())
    }

    @GetMapping("/me")
    suspend fun me(principal: Principal): LoginUser =
        loginService.find(principal.name) ?: throw JadeAuthRequiredException("로그인이 필요합니다.")

    private fun issue(user: LoginUser, response: ServerHttpResponse): LoginResponse {
        val token = tokens.issue(user.id)
        response.addCookie(tokens.cookie(token))
        return LoginResponse(id = user.id, nickname = user.nickname, token = token)
    }
}

data class LoginRequest(
    @field:NotBlank
    val id: String,

    @field:NotBlank
    val password: String,
)

data class LoginResponse(
    val id: String,
    val nickname: String,
    val token: String,
)
