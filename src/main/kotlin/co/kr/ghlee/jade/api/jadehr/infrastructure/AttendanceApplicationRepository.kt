package co.kr.ghlee.jade.api.jadehr.infrastructure

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplication
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationStatus
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface AttendanceApplicationRepository : JpaRepository<AttendanceApplication, Long> {
    fun findAllByJadeHrUserIdAndStatusAndWorkDateBetweenOrderByWorkDateAscIdAsc(
        jadeHrUserId: String,
        status: AttendanceApplicationStatus,
        from: LocalDate,
        to: LocalDate,
    ): List<AttendanceApplication>

    fun findAllByStatus(status: AttendanceApplicationStatus): List<AttendanceApplication>

    fun findAllByJadeHrUserIdAndStatus(
        jadeHrUserId: String,
        status: AttendanceApplicationStatus,
    ): List<AttendanceApplication>

    fun existsByJadeHrUserIdAndTypeAndWorkDateAndStatus(
        jadeHrUserId: String,
        type: AttendanceApplicationType,
        workDate: LocalDate,
        status: AttendanceApplicationStatus,
    ): Boolean
}
