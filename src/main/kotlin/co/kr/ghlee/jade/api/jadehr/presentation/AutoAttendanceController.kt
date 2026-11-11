package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceService
import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceSettingCommand
import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceSettingView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalTime

@RestController
@RequestMapping("/api/jadehr/auto-attendance")
class AutoAttendanceController(
    private val autoAttendanceService: AutoAttendanceService,
) {

    @GetMapping
    suspend fun get(@RequestParam id: String): AutoAttendanceSettingView =
        autoAttendanceService.get(id.trim())

    @PutMapping
    suspend fun save(
        @RequestParam id: String,
        @RequestBody request: AutoAttendanceSettingRequest,
    ): AutoAttendanceSettingView =
        autoAttendanceService.save(
            jadeHrUserId = id.trim(),
            command = AutoAttendanceSettingCommand(
                enabled = request.enabled,
                startTime = request.startTime.toLocalTimeOrNull("출근"),
                endTime = request.endTime.toLocalTimeOrNull("퇴근"),
            ),
        )

    private fun String?.toLocalTimeOrNull(label: String): LocalTime? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        require(TIME_REGEX.matches(value)) { "$label 시각은 HH:mm 형식이어야 합니다." }
        return LocalTime.parse(value)
    }

    companion object {
        private val TIME_REGEX = Regex("""([01]\d|2[0-3]):[0-5]\d""")
    }
}

data class AutoAttendanceSettingRequest(
    val enabled: Boolean = false,
    val startTime: String? = null,
    val endTime: String? = null,
)
