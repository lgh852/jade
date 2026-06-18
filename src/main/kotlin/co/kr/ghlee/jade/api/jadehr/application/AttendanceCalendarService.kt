package co.kr.ghlee.jade.api.jadehr.application

import co.kr.ghlee.jade.api.jadehr.infrastructure.client.JadeHrClient
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceCalendar
import co.kr.ghlee.jade.api.jadehr.domain.JadeHrCredential
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrSessionRequiredException
import co.kr.ghlee.jade.api.jadehr.infrastructure.parser.AttendanceCalendarParser
import co.kr.ghlee.jade.api.jadehr.infrastructure.session.JadeHrSessionStore
import org.springframework.stereotype.Service
import java.time.YearMonth

@Service
class AttendanceCalendarService(
    private val jadeHrClient: JadeHrClient,
    private val attendanceCalendarParser: AttendanceCalendarParser,
    private val sessionStore: JadeHrSessionStore,
) {

    suspend fun login(credential: JadeHrCredential) {
        val session = jadeHrClient.login(credential)
        sessionStore.save(
            companyCode = credential.companyCode,
            userId = credential.userId,
            session = session,
        )
    }

    suspend fun getCalendar(
        credential: JadeHrCredential,
        yearMonth: YearMonth,
    ): AttendanceCalendar {
        val session = jadeHrClient.login(credential)
        val html = jadeHrClient.fetchAttendanceCalendarHtml(session, yearMonth)

        return attendanceCalendarParser.parse(html, yearMonth)
    }

    suspend fun getCalendar(
        companyCode: String,
        userId: String,
        yearMonth: YearMonth,
    ): AttendanceCalendar {
        val session = sessionStore.get(companyCode, userId)
            ?: throw JadeHrSessionRequiredException("JadeHR session is missing or expired. Please login first.")

        val html = jadeHrClient.fetchAttendanceCalendarHtml(session, yearMonth)

        return attendanceCalendarParser.parse(html, yearMonth)
    }
}
