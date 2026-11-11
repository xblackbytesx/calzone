package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.Occurrence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** How an occurrence shows on one day of the agenda. */
sealed interface DayTime {
    /** An all-day event, or a day in the middle of a longer timed one. */
    data object AllDay : DayTime

    /** Starts and ends this day (`end` null when it has no length). */
    data class Range(val start: LocalTime, val end: LocalTime?) : DayTime

    /** Starts this day and goes on after midnight. */
    data class Starts(val start: LocalTime) : DayTime

    /** Began on an earlier day and ends this day. */
    data class Ends(val end: LocalTime) : DayTime
}

data class AgendaEntry(val occurrence: Occurrence, val time: DayTime)

data class AgendaDay(val date: LocalDate, val entries: List<AgendaEntry>)

/** `2026-01-05` or `2026-01-05T09:00:00` (the core's wall time) as a moment. */
internal fun wall(s: String): LocalDateTime =
    if (s.length <= 10) LocalDate.parse(s).atStartOfDay() else LocalDateTime.parse(s)

/**
 * The agenda for `[from, to)`: every day that has something, and `today`
 * always (empty when nothing is planned). An event longer than a day shows
 * on each day it covers. Within a day: all-day first, then by the time it
 * starts that day, then by title. Pure, so it is tested on the JVM.
 */
fun agendaDays(items: List<Occurrence>, from: LocalDate, to: LocalDate, today: LocalDate): List<AgendaDay> {
    val byDay = sortedMapOf<LocalDate, MutableList<AgendaEntry>>()
    for (o in items) {
        for ((day, time) in daysOf(o)) {
            if (day < from || day >= to) continue
            byDay.getOrPut(day) { mutableListOf() } += AgendaEntry(o, time)
        }
    }
    if (today >= from && today < to) byDay.getOrPut(today) { mutableListOf() }
    return byDay.map { (day, entries) -> AgendaDay(day, entries.sortedWith(order)) }
}

private val order = compareBy<AgendaEntry>(
    { if (it.time == DayTime.AllDay) 0 else 1 },
    {
        when (val t = it.time) {
            DayTime.AllDay, is DayTime.Ends -> LocalTime.MIDNIGHT
            is DayTime.Range -> t.start
            is DayTime.Starts -> t.start
        }
    },
    { it.occurrence.title.lowercase() },
)

/** The days an occurrence covers, and how it shows on each. */
internal fun daysOf(o: Occurrence): List<Pair<LocalDate, DayTime>> {
    val start = wall(o.start)
    val end = wall(o.end).let { if (it < start) start else it }
    if (o.allDay) {
        val first = start.toLocalDate()
        // The end is the day after the last day.
        val last = end.toLocalDate().minusDays(1).let { if (it < first) first else it }
        return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { it to DayTime.AllDay }.toList()
    }
    val first = start.toLocalDate()
    // An end at midnight belongs to the day before.
    val last = if (end > start && end.toLocalTime() == LocalTime.MIDNIGHT) end.toLocalDate().minusDays(1) else end.toLocalDate()
    if (last <= first) {
        return listOf(first to DayTime.Range(start.toLocalTime(), if (end > start) end.toLocalTime() else null))
    }
    return generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }.map { day ->
        day to when (day) {
            first -> DayTime.Starts(start.toLocalTime())
            last -> if (end.toLocalTime() == LocalTime.MIDNIGHT) DayTime.AllDay else DayTime.Ends(end.toLocalTime())
            else -> DayTime.AllDay
        }
    }.toList()
}
