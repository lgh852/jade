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

/** JadeHR 신청서 종류 */
enum class AttendanceApplicationType(val jadeHrApplType: String, val label: String, val formUrl: String) {
    HOME("1130", "재택 근무 신청서", "/tam4/tm_day/tm_day_170_m01.jsp"),
    LEAVE("1020", "휴가 신청서", "/tam4/tm_day/tm_day_010_m05.jsp"),
    ATTENDANCE("1110", "출퇴근시각 등록 요청서", "/tam4/tm_day/tm_day_080_m01.jsp"),
}

enum class AttendanceApplicationStatus {
    /** 신청 완료 — 결재 진행 중 (JadeHR 캘린더에는 아직 반영 전일 수 있음) */
    REQUESTED,

    /** 신청 실패 */
    FAILED,

    /** 결재 완료 (JadeHR 최종완료) */
    APPROVED,

    /** 반려 */
    REJECTED,

    /** JadeHR 에서 삭제(회수)돼 신청내역에 없음 */
    DELETED,
}

/**
 * 우리 화면에서 올린 신청서 한 건(날짜 하나).
 * 결재가 끝나기 전에는 JadeHR 캘린더에 보이지 않으므로 이 기록으로 캘린더 표시·자동 출퇴근 판단을 보완한다.
 */
@Entity
@Table(name = "jade_attendance_applications")
class AttendanceApplication(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "jadehr_user_id", nullable = false, length = 100)
    val jadeHrUserId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "appl_type", nullable = false, length = 20)
    val type: AttendanceApplicationType,

    @Column(name = "work_date", nullable = false)
    val workDate: LocalDate,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AttendanceApplicationStatus,

    /** 휴가: JadeHR 근태 코드 (4010 연차휴가 등) */
    @Column(name = "leave_type_code", length = 10)
    val leaveTypeCode: String? = null,

    @Column(name = "leave_type_name", length = 50)
    val leaveTypeName: String? = null,

    /** 휴가: ALL(종일) / AM / PM / CUSTOM */
    @Column(name = "leave_span", length = 10)
    val leaveSpan: String? = null,

    /** 출퇴근 등록 요청: 10 출근 / 20 퇴근 / 30 출퇴근 */
    @Column(name = "in_out_class", length = 10)
    val inOutClass: String? = null,

    @Column(name = "start_time")
    val startTime: LocalTime? = null,

    @Column(name = "end_time")
    val endTime: LocalTime? = null,

    @Column(name = "reason", length = 500)
    val reason: String? = null,

    @Column(name = "jadehr_appl_id", length = 30)
    var jadeHrApplId: String? = null,

    @Column(name = "message", length = 500)
    var message: String? = null,

    /** 마지막으로 JadeHR 신청내역과 맞춰 본 시각 */
    @Column(name = "synced_at")
    var syncedAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
