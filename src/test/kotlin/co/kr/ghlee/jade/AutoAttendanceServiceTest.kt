package co.kr.ghlee.jade

import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceService.Companion.isAlreadyRegistered
import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceService.Companion.isDue
import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceService.Companion.isRemoteWork
import co.kr.ghlee.jade.api.jadehr.application.AutoAttendanceSettingCommand
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType.END
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceRecordType.START
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunState
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunStatus.FAILED
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunStatus.SKIPPED
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceRunStatus.SUCCESS
import co.kr.ghlee.jade.api.jadehr.domain.AutoAttendanceSetting
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.time.LocalTime

class AutoAttendanceServiceTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val yesterday = today.minusDays(1)
    private val nine = LocalTime.of(9, 0)
    private val sixPm = LocalTime.of(18, 0)
    private val fresh = AutoAttendanceRunState(null, null, 0, null)

    @Test
    fun `설정 시각 이후 출근 구간 안에서만 실행한다`() {
        assertFalse(isDue(START, nine, fresh, today.atTime(8, 55)))
        assertTrue(isDue(START, nine, fresh, today.atTime(9, 0)))
        assertTrue(isDue(START, nine, fresh, today.atTime(9, 35)))
        assertTrue(isDue(START, nine, fresh, today.atTime(10, 0)))
        assertFalse(isDue(START, nine, fresh, today.atTime(10, 5)))
        assertFalse(isDue(START, LocalTime.of(7, 30), fresh, today.atTime(7, 25)))
    }

    @Test
    fun `퇴근은 16시30분 ~ 19시 구간에서만 실행한다`() {
        assertFalse(isDue(END, sixPm, fresh, today.atTime(17, 55)))
        assertTrue(isDue(END, sixPm, fresh, today.atTime(18, 0)))
        assertTrue(isDue(END, sixPm, fresh, today.atTime(19, 0)))
        assertFalse(isDue(END, sixPm, fresh, today.atTime(19, 5)))
        assertFalse(isDue(END, sixPm, fresh, today.atTime(9, 0)))
    }

    @Test
    fun `실패하면 2번까지 재시도하고 3번 실패하면 멈춘다`() {
        val now = today.atTime(9, 10)
        assertTrue(isDue(START, nine, AutoAttendanceRunState(today, FAILED, 1, "x"), now))
        assertTrue(isDue(START, nine, AutoAttendanceRunState(today, FAILED, 2, "x"), now))
        assertFalse(isDue(START, nine, AutoAttendanceRunState(today, FAILED, 3, "x"), now))
    }

    @Test
    fun `성공하거나 건너뛴 날은 다시 실행하지 않는다`() {
        val now = today.atTime(9, 5)
        assertFalse(isDue(START, nine, AutoAttendanceRunState(today, SUCCESS, 1, null), now))
        assertFalse(isDue(START, nine, AutoAttendanceRunState(today, SKIPPED, 1, null), now))
    }

    @Test
    fun `어제 상태는 오늘에 영향이 없다`() {
        val now = today.atTime(9, 0)
        assertTrue(isDue(START, nine, AutoAttendanceRunState(yesterday, SUCCESS, 1, null), now))
        assertTrue(isDue(START, nine, AutoAttendanceRunState(yesterday, FAILED, 3, null), now))
    }

    @Test
    fun `시도 횟수는 같은 날만 누적된다`() {
        val setting = AutoAttendanceSetting(jadeHrUserId = "u", enabled = true, startTime = nine)
        setting.recordAttempt(START, yesterday, FAILED, "a")
        setting.recordAttempt(START, yesterday, FAILED, "b")
        assertEquals(2, setting.stateOf(START).attempts)

        setting.recordAttempt(START, today, FAILED, "c")
        assertEquals(1, setting.stateOf(START).attempts)
        setting.recordAttempt(START, today, SUCCESS, "d")
        assertEquals(2, setting.stateOf(START).attempts)
        assertEquals(SUCCESS, setting.stateOf(START).status)
        assertEquals(0, setting.stateOf(END).attempts)
    }

    @Test
    fun `재택근무일만 대상이다`() {
        assertTrue(isRemoteWork("재택근무제"))
        assertFalse(isRemoteWork("선택근무제(IT)"))
        assertFalse(isRemoteWork("휴일"))
        assertFalse(isRemoteWork(null))
        assertTrue(isAlreadyRegistered("이미 현재 시간보다 빠른 출근 시간이 등록되어 있습니다. registeredStartTime=08:30"))
        assertFalse(isAlreadyRegistered("DB 작업 중 오류가 발생하였습니다."))
    }

    @Test
    fun `설정 시각은 구간 안이어야 한다`() {
        AutoAttendanceSettingCommand(true, nine, sixPm).validate()
        AutoAttendanceSettingCommand(true, LocalTime.of(7, 30), LocalTime.of(19, 0)).validate()
        AutoAttendanceSettingCommand(false, null, null).validate()
        assertThrows<IllegalArgumentException> { AutoAttendanceSettingCommand(true, null, null).validate() }
        assertThrows<IllegalArgumentException> { AutoAttendanceSettingCommand(true, LocalTime.of(7, 0), sixPm).validate() }
        assertThrows<IllegalArgumentException> { AutoAttendanceSettingCommand(true, nine, LocalTime.of(20, 0)).validate() }
    }
}
