package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.jadehr.infrastructure.config.JadeHrProperties
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceCalendar
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceWorkItem
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.domain.toWorkItems
import co.kr.ghlee.jade.api.jadehr.application.AttendanceCalendarService
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.YearMonth

@Validated
@RestController
@RequestMapping("/api/jadehr")
class AttendanceCalendarController(
    private val attendanceCalendarService: AttendanceCalendarService,
    private val properties: JadeHrProperties,
) {

    @GetMapping("/attendance-calendar")
    suspend fun getAttendanceCalendar(
        @RequestParam userId: String,
        @RequestParam @Min(2000) @Max(2100) year: Int,
        @RequestParam @Min(1) @Max(12) month: Int,
        @RequestParam(required = false) companyCode: String?,
    ): AttendanceCalendar =
        attendanceCalendarService.getCalendar(
            companyCode = companyCode ?: properties.defaultCompanyCode,
            userId = userId,
            yearMonth = YearMonth.of(year, month),
        )

    @PostMapping("/attendance-calendar")
    suspend fun getAttendanceCalendar(
        @Valid @RequestBody request: AttendanceCalendarRequest,
    ): AttendanceCalendar {
        val credential = JadeHrCredential(
            companyCode = request.companyCode ?: properties.defaultCompanyCode,
            userId = request.userId,
            password = request.password,
        )

        return attendanceCalendarService.getCalendar(
            credential = credential,
            yearMonth = YearMonth.of(request.year, request.month),
        )
    }

    @GetMapping("/attendance-calendar/work-items")
    suspend fun getAttendanceWorkItems(
        @RequestParam userId: String,
        @RequestParam @Min(2000) @Max(2100) year: Int,
        @RequestParam @Min(1) @Max(12) month: Int,
        @RequestParam(required = false) companyCode: String?,
    ): List<AttendanceWorkItem> =
        attendanceCalendarService.getCalendar(
            companyCode = companyCode ?: properties.defaultCompanyCode,
            userId = userId,
            yearMonth = YearMonth.of(year, month),
        ).toWorkItems()

    @PostMapping("/attendance-calendar/work-items")
    suspend fun getAttendanceWorkItems(
        @Valid @RequestBody request: AttendanceCalendarRequest,
    ): List<AttendanceWorkItem> {
        val credential = JadeHrCredential(
            companyCode = request.companyCode ?: properties.defaultCompanyCode,
            userId = request.userId,
            password = request.password,
        )

        return attendanceCalendarService.getCalendar(
            credential = credential,
            yearMonth = YearMonth.of(request.year, request.month),
        ).toWorkItems()
    }
}

data class AttendanceCalendarRequest(
    val companyCode: String?,

    @field:NotBlank
    val userId: String,

    @field:NotBlank
    val password: String,

    @field:Min(2000)
    @field:Max(2100)
    val year: Int,

    @field:Min(1)
    @field:Max(12)
    val month: Int,
)
