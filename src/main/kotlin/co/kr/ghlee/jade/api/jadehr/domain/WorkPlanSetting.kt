package co.kr.ghlee.jade.api.jadehr.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 근무 일정 자동 등록 결과 */
data class WorkPlanRegisterResult(
    val success: Boolean,
    /** 등록한(시험 실행이면 등록할) 날짜 */
    val dates: List<LocalDate>,
    val message: String,
)

/** 사용자별 근무 일정 자동 등록 설정 — 매주 월요일 JadeHR 이 등록 가능으로 표시한 날을 이 시간대로 등록 */
@Entity
@Table(name = "jade_work_plan_settings")
class WorkPlanSetting(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "jadehr_user_id", nullable = false, unique = true, length = 100)
    val jadeHrUserId: String,

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false,

    @Column(name = "start_time", nullable = false)
    var startTime: LocalTime = LocalTime.of(9, 30),

    @Column(name = "end_time", nullable = false)
    var endTime: LocalTime = LocalTime.of(18, 30),

    @Column(name = "last_run_at")
    var lastRunAt: LocalDateTime? = null,

    @Column(name = "last_success")
    var lastSuccess: Boolean? = null,

    @Column(name = "last_message", length = 1000)
    var lastMessage: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
