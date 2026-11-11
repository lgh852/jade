package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.auth.application.JadeAuthUserService
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationStatus
import co.kr.ghlee.jade.api.jadehr.infrastructure.AttendanceApplicationRepository
import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 결재 진행 중(REQUESTED)인 신청을 JadeHR 신청내역과 맞춘다.
 * 최종완료 → APPROVED, 반려 → REJECTED, 신청내역에 없음(삭제·회수) → DELETED.
 * 캘린더 표시와 자동 출퇴근은 REQUESTED 만 쓰므로, 반려·삭제된 신청은 여기서 빠진다.
 */
@Service
class AttendanceApplicationSyncService(
    private val repository: AttendanceApplicationRepository,
    private val jadeAuthUserService: JadeAuthUserService,
    private val jadeHrClient: JadeHrClient,
) {

    /** 최근에 맞춰 봤으면(throttle 안) 건너뛴다. force 면 항상 조회 */
    suspend fun sync(jadeHrUserId: String, force: Boolean = false) {
        val pending = withContext(Dispatchers.IO) {
            repository.findAllByJadeHrUserIdAndStatus(jadeHrUserId, AttendanceApplicationStatus.REQUESTED)
        }.filter { !it.jadeHrApplId.isNullOrBlank() }
        if (pending.isEmpty()) return

        val now = LocalDateTime.now() // 다른 시각 컬럼(created_at 등)과 같은 기준
        if (!force && pending.all { it.syncedAt?.isAfter(now.minus(THROTTLE)) == true }) return

        val principal = jadeAuthUserService.resolvePrincipalByJadeHrUserId(jadeHrUserId)
        val session = jadeHrClient.login(principal.credential)
        val from = pending.minOf { it.createdAt.toLocalDate() }.minusDays(1)
        val statuses = jadeHrClient.fetchApplicationStatuses(session, from, LocalDate.now(SEOUL_ZONE))

        withContext(Dispatchers.IO) {
            pending.forEach { app ->
                val st = statuses[app.jadeHrApplId]
                val next = when (st?.statusCode) {
                    null -> AttendanceApplicationStatus.DELETED
                    "900" -> AttendanceApplicationStatus.APPROVED
                    "300" -> AttendanceApplicationStatus.REJECTED
                    else -> AttendanceApplicationStatus.REQUESTED
                }
                if (next != app.status) {
                    log.info(
                        "Application status synced. userId={}, applId={}, date={}, {} -> {} ({})",
                        jadeHrUserId, app.jadeHrApplId, app.workDate, app.status, next, st?.statusName ?: "신청내역에 없음",
                    )
                    app.status = next
                    app.message = (st?.statusName ?: "JadeHR 신청내역에 없음(삭제·회수)").take(500)
                }
                app.syncedAt = now
                app.updatedAt = now
                repository.save(app)
            }
        }
    }

    /** 결재중 신청이 있는 모든 사용자를 대조한다 (스케줄러 회차마다 1번) */
    suspend fun syncAll() {
        val users = withContext(Dispatchers.IO) {
            repository.findAllByStatus(AttendanceApplicationStatus.REQUESTED)
        }.filter { !it.jadeHrApplId.isNullOrBlank() }.map { it.jadeHrUserId }.distinct()
        users.forEach { syncQuietly(it, force = true) }
    }

    /** 실패해도 화면·스케줄러는 계속 돌게 */
    suspend fun syncQuietly(jadeHrUserId: String, force: Boolean = false) {
        try {
            sync(jadeHrUserId, force)
        } catch (e: Exception) {
            log.warn("Application status sync failed. userId={}, message={}", jadeHrUserId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AttendanceApplicationSyncService::class.java)
        private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
        private val THROTTLE = java.time.Duration.ofMinutes(2)
    }
}
