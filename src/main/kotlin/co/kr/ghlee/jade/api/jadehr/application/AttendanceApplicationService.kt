package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplication
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationStatus
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import co.kr.ghlee.jade.api.jadehr.infrastructure.AttendanceApplicationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.YearMonth

/**
 * 우리 화면에서 올린 신청서 기록.
 * 결재 진행 중(REQUESTED)인 기록은 JadeHR 캘린더에 아직 없을 수 있어 캘린더 표시와 자동 출퇴근 판단에 함께 쓴다.
 */
@Service
class AttendanceApplicationService(
    private val repository: AttendanceApplicationRepository,
) {

    /** 해당 월의 결재 진행 중 신청 */
    suspend fun requestedOf(jadeHrUserId: String, yearMonth: YearMonth): List<AttendanceApplicationView> =
        withContext(Dispatchers.IO) {
            repository.findAllByJadeHrUserIdAndStatusAndWorkDateBetweenOrderByWorkDateAscIdAsc(
                jadeHrUserId,
                AttendanceApplicationStatus.REQUESTED,
                yearMonth.atDay(1),
                yearMonth.atEndOfMonth(),
            ).map(AttendanceApplicationView::of)
        }

    /** 그날 결재 진행 중인 재택 신청이 있는지 — 자동 출퇴근 대상 판단용 */
    suspend fun hasRequestedRemoteWork(jadeHrUserId: String, date: LocalDate): Boolean =
        withContext(Dispatchers.IO) {
            repository.existsByJadeHrUserIdAndTypeAndWorkDateAndStatus(
                jadeHrUserId,
                AttendanceApplicationType.HOME,
                date,
                AttendanceApplicationStatus.REQUESTED,
            )
        }

    /** 신청 결과를 기록한다 (신청 기능에서 호출) */
    suspend fun record(application: AttendanceApplication): AttendanceApplication =
        withContext(Dispatchers.IO) { repository.save(application) }
}

data class AttendanceApplicationView(
    val id: Long,
    val type: AttendanceApplicationType,
    val date: LocalDate,
    val status: AttendanceApplicationStatus,
    val leaveTypeName: String?,
    val leaveSpan: String?,
    val inOutClass: String?,
    val startTime: String?,
    val endTime: String?,
    /** 휴가 사용 시간(종일 8h, 오전·오후 4h, 시간 지정은 시작~종료 차이) */
    val leaveHours: Double?,
) {
    companion object {
        fun of(a: AttendanceApplication) = AttendanceApplicationView(
            id = a.id,
            type = a.type,
            date = a.workDate,
            status = a.status,
            leaveTypeName = a.leaveTypeName,
            leaveSpan = a.leaveSpan,
            inOutClass = a.inOutClass,
            startTime = a.startTime?.toString()?.take(5),
            endTime = a.endTime?.toString()?.take(5),
            leaveHours = if (a.type == AttendanceApplicationType.LEAVE) leaveHoursOf(a) else null,
        )

        private fun leaveHoursOf(a: AttendanceApplication): Double =
            when (a.leaveSpan) {
                "AM", "PM" -> 4.0
                "CUSTOM" -> {
                    val s = a.startTime
                    val e = a.endTime
                    if (s != null && e != null && e.isAfter(s)) (e.toSecondOfDay() - s.toSecondOfDay()) / 3600.0 else 0.0
                }
                else -> 8.0
            }
    }
}
