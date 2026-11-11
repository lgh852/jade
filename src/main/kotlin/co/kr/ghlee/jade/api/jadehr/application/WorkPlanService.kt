package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.domain.WorkPlanSetting
import co.kr.ghlee.jade.api.jadehr.infrastructure.WorkPlanSettingRepository
import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** 근무 일정 자동 등록 — 매주 월요일, JadeHR 이 등록 가능으로 표시한 날(당일~다음 달)을 설정 시간대로 등록(덮어쓰기) */
@Service
class WorkPlanService(
    private val repository: WorkPlanSettingRepository,
    private val jadeAuthUserService: JadeAuthUserService,
    private val jadeHrClient: JadeHrClient,
    private val notificationService: AttendanceRecordNotificationService,
) {

    suspend fun get(jadeHrUserId: String): WorkPlanSettingView {
        jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val setting = withContext(Dispatchers.IO) { repository.findByJadeHrUserId(jadeHrUserId) }
        return WorkPlanSettingView.of(jadeHrUserId, setting)
    }

    suspend fun save(jadeHrUserId: String, enabled: Boolean, startTime: LocalTime, endTime: LocalTime): WorkPlanSettingView {
        jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        require(endTime.isAfter(startTime)) { "종료 시각은 시작 시각보다 늦어야 합니다." }
        val saved = withContext(Dispatchers.IO) {
            val s = repository.findByJadeHrUserId(jadeHrUserId) ?: WorkPlanSetting(jadeHrUserId = jadeHrUserId)
            s.enabled = enabled
            s.startTime = startTime
            s.endTime = endTime
            s.updatedAt = LocalDateTime.now()
            repository.save(s)
        }
        log.info("Work plan setting saved. userId={}, enabled={}, {}~{}", jadeHrUserId, enabled, startTime, endTime)
        return WorkPlanSettingView.of(jadeHrUserId, saved)
    }

    /** 한 사람 실행. dryRun 이면 등록할 날만 찾는다. 시간대를 안 주면 저장된 설정을 쓴다 */
    suspend fun run(jadeHrUserId: String, dryRun: Boolean, startTime: LocalTime? = null, endTime: LocalTime? = null): WorkPlanRunView {
        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val setting = withContext(Dispatchers.IO) { repository.findByJadeHrUserId(jadeHrUserId) }
        val sta = startTime ?: setting?.startTime ?: throw IllegalArgumentException("일정 자동 등록 시간대를 먼저 저장하세요.")
        val end = endTime ?: setting?.endTime ?: throw IllegalArgumentException("일정 자동 등록 시간대를 먼저 저장하세요.")
        require(end.isAfter(sta)) { "종료 시각은 시작 시각보다 늦어야 합니다." }

        val thisMonth = YearMonth.now(SEOUL_ZONE)
        val result = try {
            val session = jadeHrClient.login(principal.credential)
            jadeHrClient.registerWorkPlan(session, sta, end, listOf(thisMonth, thisMonth.plusMonths(1)), dryRun)
        } catch (e: Exception) {
            log.warn("Work plan register failed. userId={}", jadeHrUserId, e)
            co.kr.ghlee.jade.api.jadehr.domain.WorkPlanRegisterResult(false, emptyList(), e.message ?: e.javaClass.simpleName)
        }

        val summary = buildString {
            append(if (dryRun) "시험 실행: " else "").append(if (result.success) "${result.dates.size}일 " else "실패 ")
            append(if (dryRun) "등록 예정" else if (result.success) "등록" else "")
            if (result.dates.isNotEmpty()) append(" (").append(result.dates.first()).append(" ~ ").append(result.dates.last()).append(")")
            if (result.message.isNotBlank()) append(" ").append(result.message)
        }.trim()
        if (!dryRun) {
            withContext(Dispatchers.IO) {
                setting?.let {
                    it.lastRunAt = LocalDateTime.now()
                    it.lastSuccess = result.success
                    it.lastMessage = summary.take(1000)
                    it.updatedAt = LocalDateTime.now()
                    repository.save(it)
                }
            }
            notificationService.sendText("[${principal.nickname}] 근무 일정 등록 ${sta.toString().take(5)}~${end.toString().take(5)}\n$summary")
        }
        return WorkPlanRunView(dryRun, result.success, result.dates, summary)
    }

    /** 스케줄러: 사용 중인 모든 사람 */
    suspend fun runAll() {
        val settings = withContext(Dispatchers.IO) { repository.findAllByEnabledTrue() }
        settings.forEach { run(it.jadeHrUserId, dryRun = false) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkPlanService::class.java)
        private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
    }
}

data class WorkPlanSettingView(
    val id: String,
    val enabled: Boolean,
    val startTime: String,
    val endTime: String,
    val lastRunAt: String?,
    val lastSuccess: Boolean?,
    val lastMessage: String?,
) {
    companion object {
        fun of(id: String, s: WorkPlanSetting?) = WorkPlanSettingView(
            id = id,
            enabled = s?.enabled ?: false,
            startTime = (s?.startTime ?: LocalTime.of(9, 30)).toString().take(5),
            endTime = (s?.endTime ?: LocalTime.of(18, 30)).toString().take(5),
            lastRunAt = s?.lastRunAt?.toString()?.replace('T', ' ')?.take(16),
            lastSuccess = s?.lastSuccess,
            lastMessage = s?.lastMessage,
        )
    }
}

data class WorkPlanRunView(
    val dryRun: Boolean,
    val success: Boolean,
    val dates: List<LocalDate>,
    val message: String,
)
