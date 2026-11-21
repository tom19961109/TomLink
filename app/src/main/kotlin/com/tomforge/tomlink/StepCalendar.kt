package com.tomforge.tomlink

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

internal data class StepInterval(val start: Long, val end: Long, val steps: Long)

internal fun mondayOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

internal fun calendarDays(month: YearMonth): List<LocalDate> {
    val first = mondayOf(month.atDay(1))
    val end = mondayOf(month.atEndOfMonth()).plusWeeks(1)
    return List(ChronoUnit.DAYS.between(first, end).toInt()) { first.plusDays(it.toLong()) }
}

internal fun dailySteps(
    intervals: Sequence<StepInterval>,
    from: LocalDate,
    until: LocalDate,
    zone: ZoneId,
): Map<LocalDate, Long> {
    val dates = List(ChronoUnit.DAYS.between(from, until).toInt()) { from.plusDays(it.toLong()) }
    val boundaries = (dates + until).map { it.atStartOfDay(zone).toInstant().toEpochMilli() }
    val totals = LongArray(dates.size)
    for ((start, end, steps) in intervals) {
        if (start >= end || steps <= 0) continue
        fun counted(at: Long): Long = when {
            at <= start -> 0
            at >= end -> steps
            else -> (steps.toDouble() * (at - start) / (end - start)).roundToLong()
        }
        val match = boundaries.binarySearch(start)
        val first = (if (match >= 0) match else -match - 2).coerceAtLeast(0)
        for (i in first until dates.size) {
            if (boundaries[i] >= end) break
            totals[i] += counted(boundaries[i + 1]) - counted(boundaries[i])
        }
    }
    return dates.indices.associate { dates[it] to totals[it] }
}
