package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordHistory
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.infrastructure.AttendanceRecordHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId

@Service
class AttendanceRecordHistoryService(
    private val repository: AttendanceRecordHistoryRepository,
) {

    suspend fun hasSuccessfulRecordToday(
        authKey: String,
        type: AttendanceRecordType,
    ): Boolean = withContext(Dispatchers.IO) {
        repository.existsByAuthKeyAndRecordTypeAndWorkDateAndSuccessTrue(
            authKey = authKey,
            recordType = type,
            workDate = LocalDate.now(SEOUL_ZONE),
        )
    }

    suspend fun save(
        authKey: String,
        credential: JadeHrCredential,
        type: AttendanceRecordType,
        result: AttendanceRecordResult,
    ) = withContext(Dispatchers.IO) {
        repository.save(
            AttendanceRecordHistory(
                authKey = authKey,
                companyCode = credential.companyCode,
                jadeHrUserId = credential.userId,
                recordType = type,
                workDate = LocalDate.now(SEOUL_ZONE),
                success = result.success,
                message = result.message,
                validationMessage = result.validationMessage,
            )
        )
    }

    companion object {
        private val SEOUL_ZONE = ZoneId.of("Asia/Seoul")
    }
}
