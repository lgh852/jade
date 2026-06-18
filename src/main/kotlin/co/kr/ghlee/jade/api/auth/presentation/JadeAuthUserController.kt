package co.kr.ghlee.jade.api.auth.presentation

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserResult
import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.auth.application.JadeAuthUserUpsertCommand
import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/jadehr/auth-users")
class JadeAuthUserController(
    private val jadeAuthUserService: JadeAuthUserService,
    private val properties: JadeHrProperties,
) {

    @PostMapping
    suspend fun upsert(
        @Valid @RequestBody request: JadeAuthUserUpsertRequest,
    ): JadeAuthUserResult =
        jadeAuthUserService.upsert(
            JadeAuthUserUpsertCommand(
                authKey = request.authKey,
                nickname = request.nickname,
                companyCode = request.companyCode ?: properties.defaultCompanyCode,
                jadeHrUserId = request.jadeHrUserId,
                jadeHrPassword = request.jadeHrPassword,
                active = request.active,
            )
        )
}

data class JadeAuthUserUpsertRequest(
    @field:NotBlank
    val authKey: String,

    val nickname: String?,

    val companyCode: String?,

    @field:NotBlank
    val jadeHrUserId: String,

    @field:NotBlank
    val jadeHrPassword: String,

    val active: Boolean = true,
)
