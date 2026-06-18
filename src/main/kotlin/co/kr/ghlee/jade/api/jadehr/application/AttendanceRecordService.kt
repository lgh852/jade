package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordPrepareResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordResult
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrSession
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrSessionRequiredException
import co.kr.ghlee.jade.api.jadehr.infrastructure.session.JadeHrSessionStore
import org.springframework.stereotype.Service

@Service
class AttendanceRecordService(
    private val jadeHrClient: JadeHrClient,
    private val sessionStore: JadeHrSessionStore,
    private val historyService: AttendanceRecordHistoryService,
    private val notificationService: AttendanceRecordNotificationService,
) {

    suspend fun prepare(
        companyCode: String,
        userId: String,
        type: AttendanceRecordType,
    ): AttendanceRecordPrepareResult {
        val session = sessionStore.get(companyCode, userId)
            ?: throw JadeHrSessionRequiredException("JadeHR session is missing or expired. Please login first.")

        return jadeHrClient.prepareAttendanceRecord(session, type)
    }

    suspend fun save(
        companyCode: String,
        userId: String,
        type: AttendanceRecordType,
    ): AttendanceRecordResult {
        val session = sessionStore.get(companyCode, userId)
            ?: throw JadeHrSessionRequiredException("JadeHR session is missing or expired. Please login first.")

        return jadeHrClient.saveAttendanceRecord(session, type)
    }

    suspend fun prepare(
        credential: JadeHrCredential,
        type: AttendanceRecordType,
    ): AttendanceRecordPrepareResult =
        withSessionRetry(credential) { session ->
            jadeHrClient.prepareAttendanceRecord(session, type)
        }

    suspend fun save(
        credential: JadeHrCredential,
        type: AttendanceRecordType,
    ): AttendanceRecordResult =
        withSessionRetry(credential) { session ->
            jadeHrClient.saveAttendanceRecord(session, type)
        }

    suspend fun saveAuto(
        authKey: String,
        nickname: String,
        credential: JadeHrCredential,
        type: AttendanceRecordType,
    ): AttendanceRecordResult {
        val result = if (historyService.hasSuccessfulRecordToday(authKey, type)) {
            AttendanceRecordResult(
                type = type,
                success = true,
                message = "오늘 이미 ${type.displayName()} 등록 성공 이력이 있어 추가 등록하지 않습니다.",
                validationMessage = null,
                etcData = mapOf("DUPLICATE_SKIPPED" to "Y"),
            )
        } else {
            save(credential, type).also { result ->
                historyService.save(authKey, credential, type, result)
            }
        }

        notificationService.send(nickname, type, result)
        return result
    }

    private fun AttendanceRecordType.displayName(): String =
        when (this) {
            AttendanceRecordType.START -> "출근"
            AttendanceRecordType.END -> "퇴근"
        }

    private suspend fun <T> withSessionRetry(
        credential: JadeHrCredential,
        block: suspend (JadeHrSession) -> T,
    ): T {
        val session = sessionStore.get(credential.companyCode, credential.userId)
            ?: loginAndSave(credential)

        return try {
            block(session)
        } catch (exception: JadeHrSessionRequiredException) {
            sessionStore.remove(credential.companyCode, credential.userId)
            block(loginAndSave(credential))
        }
    }

    private suspend fun loginAndSave(credential: JadeHrCredential): JadeHrSession {
        val session = jadeHrClient.login(credential)
        sessionStore.save(
            companyCode = credential.companyCode,
            userId = credential.userId,
            session = session,
        )
        return session
    }
}
