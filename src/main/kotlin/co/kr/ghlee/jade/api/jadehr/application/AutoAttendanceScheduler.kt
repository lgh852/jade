package co.kr.ghlee.jade.api.jadehr.application

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["jade.auto-attendance.enabled"], havingValue = "true", matchIfMissing = true)
class AutoAttendanceScheduler(
    private val autoAttendanceService: AutoAttendanceService,
    private val attendanceApplicationSyncService: AttendanceApplicationSyncService,
) {

    // 07:00~10:55, 16:00~19:55 사이 5분마다. 실제 구간(07:30~10:00, 16:30~19:00)은 AutoAttendanceService 가 거른다.
    @Scheduled(cron = "0 */5 7-10,16-19 * * *", zone = "Asia/Seoul")
    fun tick() {
        try {
            runBlocking {
                // 회차마다 1번: 결재중 신청을 JadeHR 신청내역과 대조 (반려·삭제된 재택은 자동 출퇴근에서 빠짐)
                attendanceApplicationSyncService.syncAll()
                autoAttendanceService.runDue()
            }
        } catch (exception: Exception) {
            log.warn("Auto attendance tick failed. message={}", exception.message, exception)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutoAttendanceScheduler::class.java)
    }
}
