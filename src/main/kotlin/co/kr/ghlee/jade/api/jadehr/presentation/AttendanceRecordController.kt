package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.auth.exception.JadeAuthRequiredException
import co.kr.ghlee.jade.api.jadehr.application.AttendanceRecordService
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordPrepareResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrParseException
import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
@RequestMapping("/api/jadehr/attendance-records")
class AttendanceRecordController(
    private val attendanceRecordService: AttendanceRecordService,
    private val jadeAuthUserService: JadeAuthUserService,
    private val properties: JadeHrProperties,
) {

    @PostMapping("/prepare")
    suspend fun prepare(
        @RequestBody request: AttendanceRecordRequest,
    ): AttendanceRecordPrepareResult =
        attendanceRecordService.prepare(
            companyCode = request.companyCode ?: properties.defaultCompanyCode,
            userId = request.userId,
            type = request.type,
        )

    @PostMapping("/start")
    suspend fun start(
        @RequestBody request: AttendanceRecordExecutionRequest,
    ): AttendanceRecordResult {
        request.requireConfirmed()
        return attendanceRecordService.save(
            companyCode = request.companyCode ?: properties.defaultCompanyCode,
            userId = request.userId,
            type = AttendanceRecordType.START,
        )
    }

    @PostMapping("/end")
    suspend fun end(
        @RequestBody request: AttendanceRecordExecutionRequest,
    ): AttendanceRecordResult {
        request.requireConfirmed()
        return attendanceRecordService.save(
            companyCode = request.companyCode ?: properties.defaultCompanyCode,
            userId = request.userId,
            type = AttendanceRecordType.END,
        )
    }

    @PostMapping("/prepare/auto")
    suspend fun prepareAuto(
        @RequestHeader(name = AUTH_KEY_HEADER, required = false) authKey: String?,
        @RequestParam(name = AUTH_KEY_PARAM, required = false) authKeyParam: String?,
        @RequestBody request: AttendanceRecordAutoPrepareRequest,
    ): AttendanceRecordPrepareResult =
        attendanceRecordService.prepare(
            credential = jadeAuthUserService.resolveCredential(authKeyParam.orHeader(authKey).requiredAuthKey()),
            type = request.type,
        )

    @GetMapping("/start/auto")
    suspend fun startAuto(
        @RequestHeader(name = AUTH_KEY_HEADER, required = false) authKey: String?,
        @RequestParam(name = AUTH_KEY_PARAM, required = false) authKeyParam: String?,
    ): AttendanceRecordResult =
        authKeyParam.orHeader(authKey).requiredAuthKey().let { resolvedAuthKey ->
            val principal = jadeAuthUserService.resolvePrincipal(resolvedAuthKey)
            attendanceRecordService.saveAuto(
                authKey = principal.authKey,
                nickname = principal.nickname,
                credential = principal.credential,
                type = AttendanceRecordType.START,
            )
        }

    @GetMapping("/end/auto")
    suspend fun endAuto(
        @RequestHeader(name = AUTH_KEY_HEADER, required = false) authKey: String?,
        @RequestParam(name = AUTH_KEY_PARAM, required = false) authKeyParam: String?,
    ): AttendanceRecordResult =
        authKeyParam.orHeader(authKey).requiredAuthKey().let { resolvedAuthKey ->
            val principal = jadeAuthUserService.resolvePrincipal(resolvedAuthKey)
            attendanceRecordService.saveAuto(
                authKey = principal.authKey,
                nickname = principal.nickname,
                credential = principal.credential,
                type = AttendanceRecordType.END,
            )
        }

    private fun AttendanceRecordExecutionRequest.requireConfirmed() {
        if (!confirm) {
            throw JadeHrParseException("Attendance record execution requires confirm=true.")
        }
    }

    private fun String?.requiredAuthKey(): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw JadeAuthRequiredException()

    private fun String?.orHeader(headerValue: String?): String? =
        this?.takeIf { it.isNotBlank() }
            ?: headerValue

    companion object {
        const val AUTH_KEY_HEADER = "X-Jade-Auth-Key"
        const val AUTH_KEY_PARAM = "authKey"
    }
}

data class AttendanceRecordRequest(
    val companyCode: String?,

    @field:NotBlank
    val userId: String,

    val type: AttendanceRecordType,
)

data class AttendanceRecordExecutionRequest(
    val companyCode: String?,

    @field:NotBlank
    val userId: String,

    val confirm: Boolean = false,
)

data class AttendanceRecordAutoPrepareRequest(
    val type: AttendanceRecordType,
)
