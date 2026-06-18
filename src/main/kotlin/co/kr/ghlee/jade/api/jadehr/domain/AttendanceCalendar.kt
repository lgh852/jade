package co.kr.ghlee.jade.api.jadehr.domain

import java.time.LocalDate
import java.time.LocalTime

data class AttendanceCalendar(
    val year: Int,
    val month: Int,
    val days: List<AttendanceDay>,
    val summary: AttendanceSummary,
)

data class AttendanceDay(
    val date: LocalDate,
    val workType: String?,
    val actualStartTime: LocalTime?,
    val actualEndTime: LocalTime?,
    val events: List<String>,
)

data class AttendanceSummary(
    val workDate: LocalDate?,
    val workPeriod: String?,
    val actualAndRequiredWorkTime: String?,
    val overtimeAndMaximum: String?,
    val recommendedWorkTime: String?,
    val maximumAvailableWorkTime: String?,
    val minimumRequiredWorkTime: String?,
    val accumulatedWorkTime: String?,
    val remainingWorkTime: String?,
    val overtimeWorkTime: String?,
)

data class AttendanceWorkItem(
    val date: LocalDate,
    val workType: String,
    val workTime: String?,
    val events: List<String>,
)

fun AttendanceCalendar.toWorkItems(): List<AttendanceWorkItem> =
    days
        .filter { day ->
            !day.workType.isNullOrBlank() &&
                day.workType != "휴일" &&
                (day.actualStartTime != null || day.actualEndTime != null || day.events.isNotEmpty())
        }
        .map { day ->
            AttendanceWorkItem(
                date = day.date,
                workType = day.workType.orEmpty(),
                workTime = if (day.actualStartTime != null && day.actualEndTime != null) {
                    "${day.actualStartTime}~${day.actualEndTime}"
                } else {
                    null
                },
                events = day.events,
            )
        }
