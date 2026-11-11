package co.kr.ghlee.jade

import co.kr.ghlee.jade.api.jadehr.application.AttendanceApplicationView
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplication
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationStatus.REQUESTED
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceApplicationType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class AttendanceApplicationViewTest {
    private val date = LocalDate.of(2026, 11, 20)

    private fun leave(span: String, start: LocalTime? = null, end: LocalTime? = null) =
        AttendanceApplication(
            jadeHrUserId = "u", type = AttendanceApplicationType.LEAVE, workDate = date, status = REQUESTED,
            leaveTypeCode = "4010", leaveTypeName = "연차휴가", leaveSpan = span, startTime = start, endTime = end,
        )

    @Test
    fun `휴가 사용 시간은 종일 8 오전·오후 4 시간 지정은 차이`() {
        assertEquals(8.0, AttendanceApplicationView.of(leave("ALL")).leaveHours)
        assertEquals(4.0, AttendanceApplicationView.of(leave("AM")).leaveHours)
        assertEquals(4.0, AttendanceApplicationView.of(leave("PM")).leaveHours)
        assertEquals(2.5, AttendanceApplicationView.of(leave("CUSTOM", LocalTime.of(9, 30), LocalTime.of(12, 0))).leaveHours)
        assertEquals("09:30", AttendanceApplicationView.of(leave("CUSTOM", LocalTime.of(9, 30), LocalTime.of(12, 0))).startTime)
    }

    @Test
    fun `재택 신청은 휴가 시간이 없다`() {
        val home = AttendanceApplication(jadeHrUserId = "u", type = AttendanceApplicationType.HOME, workDate = date, status = REQUESTED)
        assertNull(AttendanceApplicationView.of(home).leaveHours)
    }
}
