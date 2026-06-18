package co.kr.ghlee.jade.api.jadehr.infrastructure

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordHistory
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface AttendanceRecordHistoryRepository : JpaRepository<AttendanceRecordHistory, Long> {
    fun existsByAuthKeyAndRecordTypeAndWorkDateAndSuccessTrue(
        authKey: String,
        recordType: AttendanceRecordType,
        workDate: LocalDate,
    ): Boolean
}
