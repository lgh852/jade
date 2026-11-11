package co.kr.ghlee.jade.api.jadehr.infrastructure

import co.kr.ghlee.jade.api.jadehr.domain.WorkPlanSetting
import org.springframework.data.jpa.repository.JpaRepository

interface WorkPlanSettingRepository : JpaRepository<WorkPlanSetting, Long> {
    fun findByJadeHrUserId(jadeHrUserId: String): WorkPlanSetting?

    fun findAllByEnabledTrue(): List<WorkPlanSetting>
}
