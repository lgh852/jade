package co.kr.ghlee.jade.api.jadehr.infrastructure

import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceSetting
import org.springframework.data.jpa.repository.JpaRepository

interface AutoAttendanceSettingRepository : JpaRepository<AutoAttendanceSetting, Long> {
    fun findByJadeHrUserId(jadeHrUserId: String): AutoAttendanceSetting?

    fun findAllByEnabledTrue(): List<AutoAttendanceSetting>
}
