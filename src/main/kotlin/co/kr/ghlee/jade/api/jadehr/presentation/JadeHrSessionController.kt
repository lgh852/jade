package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.application.AttendanceCalendarService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/jadehr/session")
class JadeHrSessionController(
    private val attendanceCalendarService: AttendanceCalendarService,
    private val properties: JadeHrProperties,
) {

    @PostMapping("/login-check")
    suspend fun loginCheck(
        @Valid @RequestBody request: LoginCheckRequest,
    ): LoginCheckResponse {
        attendanceCalendarService.login(
            JadeHrCredential(
                companyCode = request.companyCode ?: properties.defaultCompanyCode,
                userId = request.userId,
                password = request.password,
            )
        )

        return LoginCheckResponse(success = true)
    }
}

data class LoginCheckRequest(
    val companyCode: String?,

    @field:NotBlank
    val userId: String,

    @field:NotBlank
    val password: String,
)

data class LoginCheckResponse(
    val success: Boolean,
)
