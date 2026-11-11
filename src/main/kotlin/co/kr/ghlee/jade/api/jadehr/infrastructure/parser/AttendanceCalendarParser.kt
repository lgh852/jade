package co.kr.ghlee.jade.api.jadehr.infrastructure.parser

import co.kr.ghlee.jade.api.jadehr.domain.AttendanceCalendar
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceDay
import co.kr.ghlee.jade.api.jadehr.domain.AttendanceSummary
import co.kr.ghlee.jade.api.jadehr.exception.JadeHrParseException
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Component
class AttendanceCalendarParser {

    fun parse(
        html: String,
        yearMonth: YearMonth,
    ): AttendanceCalendar {
        val document = Jsoup.parse(html)
        val dayCells = document.select("[onclick^=onClickTd]")

        if (dayCells.isEmpty()) {
            val bodyPreview = document.body().text().take(1_000)
            log.warn(
                "Attendance calendar cells were not found. title={}, htmlLength={}, bodyPreview={}",
                document.title(),
                html.length,
                bodyPreview,
            )
            throw JadeHrParseException(
                "Attendance calendar cells were not found. title=${document.title()}, preview=${bodyPreview.take(200)}"
            )
        }

        val days = dayCells
            .filterNot { it.classNames().contains("other") }
            .mapNotNull { cell ->
                // 2026-08 이후 JadeHR 마크업: 칸 안에 줄바꿈 없이 .day / .workItem / .workTime 태그로만 구분된다
                if (cell.selectFirst("span.day") != null) {
                    return@mapNotNull parseStructuredCell(cell, yearMonth)
                }


                val lines = cell.wholeText()
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                val dayOfMonth = lines.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
                if (dayOfMonth !in 1..yearMonth.lengthOfMonth()) return@mapNotNull null

                val timeRange = lines.firstNotNullOfOrNull { line ->
                    TIME_RANGE_REGEX.find(line)
                }

                val actualStartTime = timeRange?.groupValues?.get(1)?.let(LocalTime::parse)
                val actualEndTime = timeRange?.groupValues?.get(2)?.let(LocalTime::parse)
                val descriptions = lines.drop(1)
                    .filterNot { TIME_RANGE_REGEX.matches(it) }

                AttendanceDay(
                    date = yearMonth.atDay(dayOfMonth),
                    workType = descriptions.firstOrNull(),
                    actualStartTime = actualStartTime,
                    actualEndTime = actualEndTime,
                    events = descriptions.drop(1),
                )
            }
            .distinctBy { it.date }
            .sortedBy { it.date }

        return AttendanceCalendar(
            year = yearMonth.year,
            month = yearMonth.monthValue,
            days = days,
            summary = parseSummary(document.body().wholeText()),
        )
    }

    private fun parseStructuredCell(
        cell: Element,
        yearMonth: YearMonth,
    ): AttendanceDay? {
        // 오늘 칸은 class 속성 따옴표가 깨져 ymd 가 안 읽힐 수 있어 .day 텍스트를 우선한다
        val dayOfMonth = cell.selectFirst("span.day")?.text()?.trim()?.toIntOrNull()
            ?: cell.attr("ymd").trim().takeLast(2).toIntOrNull()
            ?: return null
        if (dayOfMonth !in 1..yearMonth.lengthOfMonth()) return null

        // 실제 기록은 일반 span, 계획 시간은 span.noWorkTime 으로 내려온다
        val actualTimes = cell.select("p.workTime span")
            .filterNot { it.hasClass("noWorkTime") }
            .flatMap { span -> TIME_REGEX.findAll(span.text()).map { LocalTime.parse(it.value) }.toList() }

        val planTimes = cell.select("p.workTime span.noWorkTime")
            .flatMap { span -> TIME_REGEX.findAll(span.text()).map { LocalTime.parse(it.value) }.toList() }

        val workItems = cell.select("span.workItem > span")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
        val listEvents = cell.select("ul.workTimeList li")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        return AttendanceDay(
            date = yearMonth.atDay(dayOfMonth),
            workType = workItems.firstOrNull(),
            actualStartTime = actualTimes.getOrNull(0),
            actualEndTime = actualTimes.getOrNull(1),
            events = workItems.drop(1) + listEvents,
            // 계획 시각: 둘이면 시작~종료, 하나면(출근만 찍힌 날) 종료 계획
            planStartTime = if (planTimes.size >= 2) planTimes[0] else null,
            planEndTime = planTimes.lastOrNull(),
        )
    }

    private fun parseSummary(text: String): AttendanceSummary {
        val normalized = text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }

        return AttendanceSummary(
            workDate = findValue(normalized, "근무일자 :")?.let { raw ->
                DATE_REGEX.find(raw)?.value?.replace(".", "-")?.let(LocalDate::parse)
            },
            workPeriod = findAfterLabel(normalized, "근무기간"),
            actualAndRequiredWorkTime = findAfterLabel(normalized, "실근로/소정"),
            overtimeAndMaximum = findAfterLabel(normalized, "연장/최대"),
            recommendedWorkTime = findAfterLabel(normalized, "권장근무시간"),
            maximumAvailableWorkTime = findAfterLabel(normalized, "최대근무가능시간"),
            minimumRequiredWorkTime = findAfterLabel(normalized, "최소근무 필요시간"),
            accumulatedWorkTime = findAfterLabel(normalized, "누적근무시간"),
            remainingWorkTime = findAfterLabel(normalized, "잔여근무시간"),
            overtimeWorkTime = findAfterLabel(normalized, "시간외근무시간"),
        )
    }

    private fun findValue(
        lines: List<String>,
        prefix: String,
    ): String? = lines.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()

    private fun findAfterLabel(
        lines: List<String>,
        label: String,
    ): String? {
        val index = lines.indexOfFirst { it == label || it.startsWith(label) }
        if (index < 0) return null

        val sameLineValue = lines[index].removePrefix(label).trim()
        return sameLineValue.ifBlank { lines.getOrNull(index + 1) }
    }

    companion object {
        private val log = LoggerFactory.getLogger(AttendanceCalendarParser::class.java)
        private val TIME_RANGE_REGEX = Regex("""(\d{2}:\d{2})~(\d{2}:\d{2})""")
        private val TIME_REGEX = Regex("""\d{2}:\d{2}""")
        private val DATE_REGEX = Regex("""\d{4}\.\d{2}\.\d{2}""")
    }
}
