package co.kr.ghlee.jade.api.jadehr.application

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(name = ["jade.work-plan.enabled"], havingValue = "true", matchIfMissing = true)
class WorkPlanScheduler(
    private val workPlanService: WorkPlanService,
) {

    /** 매주 월요일 07:00 */
    @Scheduled(cron = "0 0 7 * * MON", zone = "Asia/Seoul")
    fun weekly() {
        try {
            runBlocking { workPlanService.runAll() }
        } catch (e: Exception) {
            log.warn("Work plan weekly run failed. message={}", e.message, e)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WorkPlanScheduler::class.java)
    }
}
