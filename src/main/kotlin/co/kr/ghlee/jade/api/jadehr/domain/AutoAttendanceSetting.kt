package co.kr.ghlee.jade.api.jadehr.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

enum class AutoAttendanceRunStatus {
    /** 등록 성공 (이미 성공 이력이 있어 건너뛴 경우 포함) */
    SUCCESS,

    /** 실패 — 시도 횟수가 남아 있으면 다음 회차에 재시도 */
    FAILED,

    /** 재택근무일이 아니거나 이미 더 이른 출근이 있어 건너뜀 */
    SKIPPED,
}

/** 출근·퇴근 하나에 대한 그날의 실행 상태 */
data class AutoAttendanceRunState(
    val runDate: LocalDate?,
    val status: AutoAttendanceRunStatus?,
    val attempts: Int,
    val message: String?,
) {
    /** 오늘 기준 상태. 날짜가 다르면 아직 시도하지 않은 것으로 본다. */
    fun on(today: LocalDate): AutoAttendanceRunState =
        if (runDate == today) this else AutoAttendanceRunState(today, null, 0, null)
}

/**
 * 사용자별 자동 출퇴근 설정. 재택근무일에만 start/end 시각 이후 회차에 자동 등록한다.
 */
@Entity
@Table(name = "jade_auto_attendance_settings")
class AutoAttendanceSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "jadehr_user_id", nullable = false, unique = true, length = 100)
    val jadeHrUserId: String,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "start_time")
    var startTime: LocalTime? = null,

    @Column(name = "end_time")
    var endTime: LocalTime? = null,

    @Column(name = "start_run_date")
    var startRunDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "start_status", length = 20)
    var startStatus: AutoAttendanceRunStatus? = null,

    @Column(name = "start_attempts", nullable = false)
    var startAttempts: Int = 0,

    @Column(name = "start_message", length = 500)
    var startMessage: String? = null,

    @Column(name = "end_run_date")
    var endRunDate: LocalDate? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "end_status", length = 20)
    var endStatus: AutoAttendanceRunStatus? = null,

    @Column(name = "end_attempts", nullable = false)
    var endAttempts: Int = 0,

    @Column(name = "end_message", length = 500)
    var endMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun timeOf(type: AttendanceRecordType): LocalTime? =
        when (type) {
            AttendanceRecordType.START -> startTime
            AttendanceRecordType.END -> endTime
        }

    fun stateOf(type: AttendanceRecordType): AutoAttendanceRunState =
        when (type) {
            AttendanceRecordType.START -> AutoAttendanceRunState(startRunDate, startStatus, startAttempts, startMessage)
            AttendanceRecordType.END -> AutoAttendanceRunState(endRunDate, endStatus, endAttempts, endMessage)
        }

    /** 한 번 시도한 결과를 기록한다. 같은 날이면 시도 횟수를 누적한다. */
    fun recordAttempt(
        type: AttendanceRecordType,
        today: LocalDate,
        status: AutoAttendanceRunStatus,
        message: String?,
    ) {
        val attempts = stateOf(type).on(today).attempts + 1
        val trimmed = message?.take(500)
        when (type) {
            AttendanceRecordType.START -> {
                startRunDate = today; startStatus = status; startAttempts = attempts; startMessage = trimmed
            }
            AttendanceRecordType.END -> {
                endRunDate = today; endStatus = status; endAttempts = attempts; endMessage = trimmed
            }
        }
        updatedAt = LocalDateTime.now()
    }

    fun update(enabled: Boolean, startTime: LocalTime?, endTime: LocalTime?) {
        this.enabled = enabled
        this.startTime = startTime
        this.endTime = endTime
        this.updatedAt = LocalDateTime.now()
    }
}
