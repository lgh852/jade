package co.kr.ghlee.jade.api.jadehr.presentation

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.application.AttendanceApplicationService
import co.kr.ghlee.jade.api.jadehr.application.AttendanceApplicationView
import co.kr.ghlee.jade.api.jadehr.application.ApprovalLineService
import co.kr.ghlee.jade.api.jadehr.application.ApplicationItem
import co.kr.ghlee.jade.api.jadehr.application.ApplicationSubmitCommand
import co.kr.ghlee.jade.api.jadehr.application.ApplicationSubmitView
import co.kr.ghlee.jade.api.jadehr.application.AttendanceApplicationSubmitService
import co.kr.ghlee.jade.api.jadehr.application.AttendanceApplicationSyncService
import co.kr.ghlee.jade.api.jadehr.application.ApprovalLineView
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.YearMonth

@Validated
@RestController
@RequestMapping("/api/jadehr/applications")
class AttendanceApplicationController(
    private val attendanceApplicationService: AttendanceApplicationService,
    private val jadeAuthUserService: JadeAuthUserService,
    private val approvalLineService: ApprovalLineService,
    private val submitService: AttendanceApplicationSubmitService,
    private val syncService: AttendanceApplicationSyncService,
) {

    /** 신청: 고른 날짜마다 JadeHR 신청서 1건. dryRun=true 면 신청 직전까지만 확인 */
    @PostMapping
    suspend fun submit(
        @RequestParam id: String,
        @RequestBody request: ApplicationSubmitRequest,
    ): ApplicationSubmitView = submitService.submit(
        id.trim(),
        ApplicationSubmitCommand(
            type = request.type,
            dates = request.dates.map { LocalDate.parse(it) },
            reason = request.reason.orEmpty(),
            approverEmpIds = request.approverEmpIds,
            dryRun = request.dryRun,
            items = request.items.associate { item ->
                LocalDate.parse(item.date) to ApplicationItem(
                    inOutClass = item.inOutClass,
                    leaveTypeCode = item.leaveTypeCode,
                    leaveSpan = item.leaveSpan,
                    startTime = item.startTime?.takeIf { it.isNotBlank() }?.let { java.time.LocalTime.parse(it) },
                    endTime = item.endTime?.takeIf { it.isNotBlank() }?.let { java.time.LocalTime.parse(it) },
                )
            },
        ),
    )

    /** 신청 팝업용: JadeHR 신청서를 열어 받은 기본 결재선 (조회만) */
    @GetMapping("/approval-line")
    suspend fun approvalLine(
        @RequestParam id: String,
        @RequestParam type: AttendanceApplicationType,
    ): ApprovalLineView = approvalLineService.get(id.trim(), type)

    /** 캘린더 화면용: 해당 월의 결재 진행 중 신청 */
    @GetMapping
    suspend fun requested(
        @RequestParam id: String,
        @RequestParam @Min(2000) @Max(2100) year: Int,
        @RequestParam @Min(1) @Max(12) month: Int,
    ): List<AttendanceApplicationView> {
        val userId = id.trim()
        jadeAuthUserService.resolvePrincipalByJadeHrUserId(userId) // 등록된 아이디만
        // 결재중 신청이 있을 때만 JadeHR 신청내역과 대조해 반려·삭제를 반영한다 (2분 안에 대조했으면 건너뜀)
        syncService.syncQuietly(userId)
        return attendanceApplicationService.requestedOf(userId, YearMonth.of(year, month))
    }
}

data class ApplicationSubmitRequest(
    val type: AttendanceApplicationType,
    val dates: List<String> = emptyList(),
    val reason: String? = null,
    val approverEmpIds: List<String> = emptyList(),
    val dryRun: Boolean = false,
    val items: List<ApplicationItemRequest> = emptyList(),
)

data class ApplicationItemRequest(
    val date: String,
    val inOutClass: String? = null,
    val leaveTypeCode: String? = null,
    val leaveSpan: String? = null,
    val startTime: String? = null,
    val endTime: String? = null,
)
