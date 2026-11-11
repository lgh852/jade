package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplication
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationStatus
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 캘린더 화면에서 고른 날짜로 JadeHR 신청서를 올린다. 날짜마다 1건.
 * 결재선은 화면에서 고른 결재자(기본 결재선 중 일부)를 그대로 보낸다.
 */
@Service
class AttendanceApplicationSubmitService(
    private val jadeAuthUserService: JadeAuthUserService,
    private val jadeHrClient: JadeHrClient,
    private val attendanceApplicationService: AttendanceApplicationService,
) {

    suspend fun submit(jadeHrUserId: String, command: ApplicationSubmitCommand): ApplicationSubmitView {
        command.validate()
        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val session = jadeHrClient.login(principal.credential)

        val results = command.dates.sorted().map { date ->
            val item = command.items[date]
            val result = try {
                when (command.type) {
                    AttendanceApplicationType.LEAVE -> {
                        val leaveType = item?.leaveTypeCode ?: "4010"
                        val span = item?.leaveSpan ?: "ALL"
                        if (span != "ALL" && (item?.startTime == null || item.endTime == null)) {
                            co.kr.ghlee.jade.api.jadehr.domain.ApplicationSubmitResult(false, "부분 휴가는 시작·종료 시각을 입력하세요.", null)
                        } else jadeHrClient.submitLeave(
                            session = session,
                            workDate = date,
                            attendClass = leaveType,
                            partStart = if (span == "ALL") null else item?.startTime,
                            partEnd = if (span == "ALL") null else item?.endTime,
                            reason = command.reason.trim(),
                            approverEmpIds = command.approverEmpIds,
                            dryRun = command.dryRun,
                        )
                    }
                    AttendanceApplicationType.ATTENDANCE -> {
                        requireNotNull(item?.inOutClass) { "${date}: 출근/퇴근 구분을 선택하세요." }
                        jadeHrClient.submitAttendanceRequest(
                            session = session,
                            workDate = date,
                            inOutClass = item!!.inOutClass!!,
                            startTime = item.startTime,
                            endTime = item.endTime,
                            reason = command.reason.trim(),
                            approverEmpIds = command.approverEmpIds,
                            dryRun = command.dryRun,
                        )
                    }
                    AttendanceApplicationType.HOME -> jadeHrClient.submitRemoteWork(
                        session = session,
                        workDate = date,
                        reason = command.reason.trim(),
                        approverEmpIds = command.approverEmpIds,
                        dryRun = command.dryRun,
                    )
                    else -> throw IllegalArgumentException("${command.type.label} 신청은 아직 준비 중입니다.")
                }
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                log.warn("Application submit failed. userId={}, type={}, date={}", jadeHrUserId, command.type, date, e)
                co.kr.ghlee.jade.api.jadehr.domain.ApplicationSubmitResult(false, e.message ?: e.javaClass.simpleName, null)
            }

            if (!command.dryRun) {
                attendanceApplicationService.record(
                    AttendanceApplication(
                        jadeHrUserId = jadeHrUserId,
                        type = command.type,
                        workDate = date,
                        status = if (result.success) AttendanceApplicationStatus.REQUESTED else AttendanceApplicationStatus.FAILED,
                        inOutClass = item?.inOutClass,
                        leaveTypeCode = if (command.type == AttendanceApplicationType.LEAVE) item?.leaveTypeCode ?: "4010" else null,
                        leaveTypeName = if (command.type == AttendanceApplicationType.LEAVE) LEAVE_NAMES[item?.leaveTypeCode ?: "4010"] else null,
                        leaveSpan = if (command.type == AttendanceApplicationType.LEAVE) item?.leaveSpan ?: "ALL" else null,
                        startTime = item?.startTime,
                        endTime = item?.endTime,
                        reason = command.reason.trim().take(500),
                        jadeHrApplId = result.applId,
                        message = result.message.take(500),
                    ),
                )
            }
            log.info(
                "Application submit. userId={}, type={}, date={}, dryRun={}, success={}, applId={}, message={}",
                jadeHrUserId, command.type, date, command.dryRun, result.success, result.applId, result.message,
            )
            ApplicationDateResult(date, result.success, result.message, result.applId)
        }
        return ApplicationSubmitView(command.type, command.dryRun, results)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AttendanceApplicationSubmitService::class.java)
        private val LEAVE_NAMES = mapOf(
            "4010" to "연차휴가", "5010" to "휴일대체", "5020" to "휴일대체(오전)",
            "5030" to "휴일대체(오후)", "6020" to "포상휴가", "6090" to "귀임휴가",
        )
    }
}

data class ApplicationSubmitCommand(
    val type: AttendanceApplicationType,
    val dates: List<LocalDate>,
    val reason: String,
    /** 결재선에 넣을 결재자 사번 (순서대로) */
    val approverEmpIds: List<String>,
    /** true 면 신청 직전까지만 실행 */
    val dryRun: Boolean = false,
    /** 날짜별 세부 값 (출퇴근: 구분·시각) */
    val items: Map<LocalDate, ApplicationItem> = emptyMap(),
) {
    fun validate() {
        require(dates.isNotEmpty()) { "신청할 날짜를 선택하세요." }
        require(dates.size <= 31) { "한 번에 31일까지 신청할 수 있습니다." }
        require(dates.toSet().size == dates.size) { "같은 날짜가 두 번 들어 있습니다." }
        require(reason.isNotBlank()) { "신청 사유를 입력하세요." }
        require(reason.length <= 500) { "신청 사유는 500자 이하로 입력하세요." }
        require(approverEmpIds.isNotEmpty()) { "결재자는 한 명 이상 있어야 합니다." }
    }
}

data class ApplicationDateResult(
    val date: LocalDate,
    val success: Boolean,
    val message: String,
    val applId: String?,
)

data class ApplicationSubmitView(
    val type: AttendanceApplicationType,
    val dryRun: Boolean,
    val results: List<ApplicationDateResult>,
)

data class ApplicationItem(
    /** 출퇴근 등록 요청: 10 출근 / 20 퇴근 / 30 출퇴근 */
    val inOutClass: String? = null,
    /** 휴가: 근태 코드 (4010 연차휴가 등) */
    val leaveTypeCode: String? = null,
    /** 휴가: ALL 종일 / AM / PM / CUSTOM */
    val leaveSpan: String? = null,
    val startTime: java.time.LocalTime? = null,
    val endTime: java.time.LocalTime? = null,
)
