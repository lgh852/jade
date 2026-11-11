package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunState
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunStatus
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceSetting
import co.kr.ghlee.jade.api.jadehr.infrastructure.AutoAttendanceSettingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

@Service
class AutoAttendanceService(
    private val repository: AutoAttendanceSettingRepository,
    private val jadeAuthUserService: JadeAuthUserService,
    private val attendanceCalendarService: AttendanceCalendarService,
    private val attendanceRecordService: AttendanceRecordService,
    private val attendanceApplicationService: AttendanceApplicationService,
) {

    suspend fun get(jadeHrUserId: String): AutoAttendanceSettingView {
        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val setting = withContext(Dispatchers.IO) { repository.findByJadeHrUserId(jadeHrUserId) }
        return AutoAttendanceSettingView.of(jadeHrUserId, principal.nickname, setting, today())
    }

    suspend fun save(jadeHrUserId: String, command: AutoAttendanceSettingCommand): AutoAttendanceSettingView {
        // 등록된 아이디만 설정 가능
        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        command.validate()

        val saved = withContext(Dispatchers.IO) {
            val setting = repository.findByJadeHrUserId(jadeHrUserId)
                ?: AutoAttendanceSetting(jadeHrUserId = jadeHrUserId)
            setting.update(command.enabled, command.startTime, command.endTime)
            repository.save(setting)
        }
        log.info(
            "Auto attendance setting saved. userId={}, enabled={}, start={}, end={}",
            jadeHrUserId, saved.enabled, saved.startTime, saved.endTime,
        )
        return AutoAttendanceSettingView.of(jadeHrUserId, principal.nickname, saved, today())
    }

    /** 스케줄러가 실행 구간 안에서 5분마다 호출한다. */
    suspend fun runDue(now: LocalDateTime = LocalDateTime.now(SEOUL_ZONE)) {
        val settings = withContext(Dispatchers.IO) { repository.findAllByEnabledTrue() }
        for (setting in settings) {
            for (type in AttendanceRecordType.entries) {
                if (!isDue(type, setting.timeOf(type), setting.stateOf(type), now)) continue
                runOne(setting, type, now.toLocalDate())
            }
        }
    }

    private suspend fun runOne(setting: AutoAttendanceSetting, type: AttendanceRecordType, today: LocalDate) {
        val userId = setting.jadeHrUserId
        val attempt = setting.stateOf(type).on(today).attempts + 1

        val (status, message) = try {
            val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(userId)
            val calendar = attendanceCalendarService.getCalendar(principal.credential, YearMonth.from(today))
            val workType = calendar.days.firstOrNull { it.date == today }?.workType

            // 결재 진행 중인 재택 신청도 재택일로 본다 (JadeHR 캘린더는 결재가 끝나야 반영됨)
            // 결재중 재택 신청도 재택으로 본다 (반려·삭제는 스케줄러 회차 시작 때 대조해 빠져 있음)
            val remote = isRemoteWork(workType) || attendanceApplicationService.hasRequestedRemoteWork(userId, today)

            if (!remote) {
                AutoAttendanceRunStatus.SKIPPED to "재택근무일이 아니라 건너뜀 (근무유형: ${workType ?: "없음"})"
            } else {
                val result = attendanceRecordService.saveAuto(
                    authKey = principal.authKey,
                    nickname = principal.nickname,
                    credential = principal.credential,
                    type = type,
                )
                when {
                    result.success -> AutoAttendanceRunStatus.SUCCESS to (result.message?.ifBlank { null } ?: "등록 성공")
                    isAlreadyRegistered(result.message) -> AutoAttendanceRunStatus.SKIPPED to result.message
                    else -> AutoAttendanceRunStatus.FAILED to (result.message ?: "등록 실패")
                }
            }
        } catch (exception: Exception) {
            log.warn("Auto attendance failed. userId={}, type={}, attempt={}", userId, type, attempt, exception)
            AutoAttendanceRunStatus.FAILED to (exception.message ?: exception.javaClass.simpleName)
        }

        withContext(Dispatchers.IO) {
            setting.recordAttempt(type, today, status, message)
            repository.save(setting)
        }
        log.info(
            "Auto attendance run. userId={}, type={}, attempt={}/{}, status={}, message={}",
            userId, type, attempt, MAX_ATTEMPTS, status, message,
        )
    }

    private fun today(): LocalDate = LocalDate.now(SEOUL_ZONE)

    companion object {
        private val log = LoggerFactory.getLogger(AutoAttendanceService::class.java)
        private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")

        /** 첫 시도 + 재시도 2회 (5분 간격이라 실패 후 10분 동안 재시도) */
        const val MAX_ATTEMPTS = 3

        val START_WINDOW: ClosedRange<LocalTime> = LocalTime.of(7, 30)..LocalTime.of(10, 0)
        val END_WINDOW: ClosedRange<LocalTime> = LocalTime.of(16, 30)..LocalTime.of(19, 0)

        fun windowOf(type: AttendanceRecordType): ClosedRange<LocalTime> =
            when (type) {
                AttendanceRecordType.START -> START_WINDOW
                AttendanceRecordType.END -> END_WINDOW
            }

        /**
         * 실행 여부.
         * - 출근/퇴근 구간 안이고, 설정 시각이 지났고
         * - 오늘 성공·건너뜀으로 끝나지 않았고, 시도 횟수가 남아 있을 때
         */
        fun isDue(
            type: AttendanceRecordType,
            time: LocalTime?,
            state: AutoAttendanceRunState,
            now: LocalDateTime,
        ): Boolean {
            if (time == null) return false
            val nowTime = now.toLocalTime()
            if (nowTime !in windowOf(type)) return false
            if (nowTime.isBefore(time)) return false

            val today = state.on(now.toLocalDate())
            return when (today.status) {
                AutoAttendanceRunStatus.SUCCESS, AutoAttendanceRunStatus.SKIPPED -> false
                AutoAttendanceRunStatus.FAILED, null -> today.attempts < MAX_ATTEMPTS
            }
        }

        fun isRemoteWork(workType: String?): Boolean = workType?.contains("재택") == true

        /** JadeHrClient 가 이미 더 이른 출근이 있어 저장을 막은 경우 — 재시도해도 같은 결과라 건너뜀 처리 */
        fun isAlreadyRegistered(message: String?): Boolean =
            message?.contains("이미 현재 시간보다 빠른 출근 시간") == true
    }
}

data class AutoAttendanceSettingCommand(
    val enabled: Boolean,
    val startTime: LocalTime?,
    val endTime: LocalTime?,
) {
    fun validate() {
        require(!enabled || startTime != null || endTime != null) { "출근 또는 퇴근 시각을 하나 이상 입력하세요." }
        startTime?.let {
            require(it in AutoAttendanceService.START_WINDOW) { "출근 시각은 07:30 ~ 10:00 사이여야 합니다." }
        }
        endTime?.let {
            require(it in AutoAttendanceService.END_WINDOW) { "퇴근 시각은 16:30 ~ 19:00 사이여야 합니다." }
        }
    }
}

data class AutoAttendanceRunView(
    val status: String?,
    val attempts: Int,
    val message: String?,
)

data class AutoAttendanceSettingView(
    val id: String,
    val nickname: String,
    val enabled: Boolean,
    val startTime: String?,
    val endTime: String?,
    /** 오늘 출근 실행 결과 (아직 실행 전이면 status=null) */
    val todayStart: AutoAttendanceRunView,
    /** 오늘 퇴근 실행 결과 */
    val todayEnd: AutoAttendanceRunView,
    val maxAttempts: Int = AutoAttendanceService.MAX_ATTEMPTS,
    val remoteWorkOnly: Boolean = true,
) {
    companion object {
        fun of(id: String, nickname: String, setting: AutoAttendanceSetting?, today: LocalDate) =
            AutoAttendanceSettingView(
                id = id,
                nickname = nickname,
                enabled = setting?.enabled ?: false,
                startTime = setting?.startTime?.toString()?.take(5),
                endTime = setting?.endTime?.toString()?.take(5),
                todayStart = setting.runView(AttendanceRecordType.START, today),
                todayEnd = setting.runView(AttendanceRecordType.END, today),
            )

        private fun AutoAttendanceSetting?.runView(type: AttendanceRecordType, today: LocalDate): AutoAttendanceRunView {
            val state = this?.stateOf(type)?.on(today) ?: AutoAttendanceRunState(today, null, 0, null)
            return AutoAttendanceRunView(state.status?.name, state.attempts, state.message)
        }
    }
}
