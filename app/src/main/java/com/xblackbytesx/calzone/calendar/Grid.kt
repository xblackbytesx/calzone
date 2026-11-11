package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.Occurrence
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The calendar's views (decisions 27 and 33). Month and days, a small month
 * over the list of its days as Fossify has it, is the default.
 */
enum class CalendarView { MONTH_DAY, AGENDA, DAY, WEEK, MONTH }

/** The first day of `d`'s week, for a week that starts on `first`. */
fun weekStart(d: LocalDate, first: DayOfWeek): LocalDate = d.with(TemporalAdjusters.previousOrSame(first))

/** The days of `d`'s week. */
fun weekOf(d: LocalDate, first: DayOfWeek): List<LocalDate> = weekStart(d, first).let { s -> (0L until 7L).map(s::plusDays) }

/** The weeks a month's grid shows: from the week of the 1st to the week of the last day. */
fun monthWeeks(month: LocalDate, first: DayOfWeek): List<List<LocalDate>> {
    val start = weekStart(month.withDayOfMonth(1), first)
    val last = month.withDayOfMonth(month.lengthOfMonth())
    return generateSequence(start) { it.plusWeeks(1) }.takeWhile { it <= last }.map { s -> (0L until 7L).map(s::plusDays) }.toList()
}

/**
 * Swipe to page: a page is a day, a week or a month, numbered from 1970 so
 * a date always has the same page. The pagers reach to 2200.
 */
object Pages {
    private val END: LocalDate = LocalDate.of(2200, 1, 1)
    private val EPOCH: LocalDate = LocalDate.ofEpochDay(0)

    fun count(view: CalendarView, first: DayOfWeek): Int = of(view, END, first) + 1

    fun of(view: CalendarView, d: LocalDate, first: DayOfWeek): Int = when (view) {
        CalendarView.AGENDA, CalendarView.DAY -> d.toEpochDay().toInt()
        CalendarView.WEEK -> ChronoUnit.WEEKS.between(weekStart(EPOCH, first), weekStart(d, first)).toInt()
        CalendarView.MONTH, CalendarView.MONTH_DAY -> (d.year - 1970) * 12 + d.monthValue - 1
    }.coerceAtLeast(0)

    /**
     * The date a page shows, keeping `from`'s place in it: the same weekday
     * a week later, the same day of the month a month later.
     */
    fun date(view: CalendarView, page: Int, from: LocalDate, first: DayOfWeek): LocalDate {
        val delta = (page - of(view, from, first)).toLong()
        return when (view) {
            CalendarView.AGENDA, CalendarView.DAY -> from.plusDays(delta)
            CalendarView.WEEK -> from.plusWeeks(delta)
            CalendarView.MONTH, CalendarView.MONTH_DAY -> from.plusMonths(delta)
        }
    }
}

/**
 * An all-day event, or a timed one of a day or longer: those go in the
 * row above the hours, as one bar over the days they cover. A shorter one
 * that runs past midnight (a night shift) stays in the hours, in two parts.
 */
fun onAllDayRow(o: Occurrence): Boolean {
    if (o.allDay) return true
    val start = wall(o.start)
    val end = wall(o.end)
    return Duration.between(start, end) >= Duration.ofDays(1)
}

/** A timed event on one day of the grid, in minutes from midnight, and its column among those it overlaps. */
data class Piece(val occurrence: Occurrence, val start: Int, val end: Int, val col: Int, val cols: Int)

/**
 * The timed events of `day`, laid out side by side where they overlap:
 * events that overlap (directly or through others) share the width, each
 * in the first column free at its start. An event shorter than
 * `minLength` minutes takes that much room, so its title fits.
 */
fun timedPieces(items: List<Occurrence>, day: LocalDate, minLength: Int = 30): List<Piece> {
    data class Raw(val o: Occurrence, val start: Int, val end: Int)
    val raw = items.filterNot(::onAllDayRow).mapNotNull { o ->
        val t = daysOf(o).firstOrNull { it.first == day }?.second ?: return@mapNotNull null
        when (t) {
            is DayTime.Range -> {
                val s = t.start.toSecondOfDay() / 60
                // An end at midnight is the end of this day.
                val e = t.end?.let { if (it <= t.start) 1440 else it.toSecondOfDay() / 60 } ?: s
                Raw(o, s, e)
            }
            is DayTime.Starts -> Raw(o, t.start.toSecondOfDay() / 60, 1440)
            is DayTime.Ends -> Raw(o, 0, t.end.toSecondOfDay() / 60)
            DayTime.AllDay -> null
        }
    }.sortedWith(compareBy<Raw>({ it.start }, { -it.end }, { it.o.title.lowercase() }))

    val out = mutableListOf<Piece>()
    var cluster = mutableListOf<Pair<Raw, Int>>()
    var columns = mutableListOf<Int>()
    var clusterEnd = -1
    fun close() {
        cluster.forEach { (r, col) -> out += Piece(r.o, r.start, r.end, col, columns.size) }
        cluster = mutableListOf()
        columns = mutableListOf()
    }
    for (r in raw) {
        val shownEnd = maxOf(r.end, r.start + minLength)
        if (r.start >= clusterEnd) close()
        val col = columns.indexOfFirst { it <= r.start }.let { if (it >= 0) it else columns.size }
        if (col == columns.size) columns += shownEnd else columns[col] = shownEnd
        cluster += r to col
        clusterEnd = maxOf(clusterEnd, shownEnd)
    }
    close()
    return out
}

/** A bar in a row of days: from column `first` to `last`, in its lane (0 on top). */
data class Span(val occurrence: Occurrence, val first: Int, val last: Int, val lane: Int, val bar: Boolean)

/**
 * The bars of a row of days (a week's all-day row, a month's week). Events
 * on the all-day row are bars over the days they cover; with `timed`, the
 * other events are one-day entries on the day they start. Longer bars get
 * the top lanes, then all-day before timed, then by start and title.
 */
fun spans(items: List<Occurrence>, days: List<LocalDate>, timed: Boolean): List<Span> {
    if (days.isEmpty()) return emptyList()
    data class Raw(val o: Occurrence, val first: Int, val last: Int, val bar: Boolean)
    val index = days.withIndex().associate { (i, d) -> d to i }
    val raw = items.mapNotNull { o ->
        if (onAllDayRow(o)) {
            val covered = daysOf(o).mapNotNull { index[it.first] }
            if (covered.isEmpty()) null else Raw(o, covered.min(), covered.max(), true)
        } else if (timed) {
            index[wall(o.start).toLocalDate()]?.let { Raw(o, it, it, false) }
        } else {
            null
        }
    }.sortedWith(
        compareBy<Raw>(
            { -(it.last - it.first) },
            { if (it.bar) 0 else 1 },
            { it.o.start },
            { it.o.title.lowercase() },
        ),
    )
    val lanes = mutableListOf<BooleanArray>()
    return raw.map { r ->
        var lane = lanes.indexOfFirst { taken -> (r.first..r.last).none { taken[it] } }
        if (lane < 0) {
            lanes += BooleanArray(days.size)
            lane = lanes.size - 1
        }
        for (i in r.first..r.last) lanes[lane][i] = true
        Span(r.o, r.first, r.last, lane, r.bar)
    }
}

/** The events of `day` in agenda order, for the list a day opens (month view, "+2"). */
fun dayEntries(items: List<Occurrence>, day: LocalDate): AgendaDay =
    agendaDays(items, day, day.plusDays(1), day).firstOrNull() ?: AgendaDay(day, emptyList())

/** Where the hours open: an hour and a half before now on today, else 7:00. */
fun openingMinute(today: Boolean, now: LocalTime): Int =
    if (today) (now.toSecondOfDay() / 60 - 90).coerceIn(0, 16 * 60) else 7 * 60

/**
 * The colors of the calendars with something on each of `days`, for the
 * small month's dots: each calendar once, in the order its first event
 * of the day comes, at most `max`.
 */
fun dayColors(items: List<Occurrence>, days: List<LocalDate>, max: Int = 3): Map<LocalDate, List<String>> {
    if (days.isEmpty()) return emptyMap()
    val first = days.min()
    val last = days.max()
    val out = linkedMapOf<LocalDate, MutableList<String>>()
    for (day in agendaDays(items, first, last.plusDays(1), first.minusDays(1))) {
        val colors = day.entries.map { it.occurrence.color }.distinct().take(max)
        if (colors.isNotEmpty()) out[day.date] = colors.toMutableList()
    }
    return out
}

/** The days of `month` with something on them, and today when it is in the month: the list under the small month. */
fun monthDays(items: List<Occurrence>, month: LocalDate, today: LocalDate): List<AgendaDay> {
    val first = month.withDayOfMonth(1)
    return agendaDays(items, first, first.plusMonths(1), today)
}

/**
 * The loaded days by date (decision 37): built once per load, off the main
 * thread, so a page only looks its days up instead of going through every
 * occurrence again while it swipes in.
 */
typealias DayIndex = Map<LocalDate, AgendaDay>

/** The occurrences on any of `days`, each once, in day order. */
fun itemsOn(index: DayIndex, days: List<LocalDate>): List<Occurrence> =
    days.flatMap { d -> index[d]?.entries.orEmpty().map { it.occurrence } }.distinct()

/** As [dayColors], from the index. */
fun dayColorsOf(index: DayIndex, days: List<LocalDate>, max: Int = 3): Map<LocalDate, List<String>> =
    days.mapNotNull { d ->
        index[d]?.entries?.map { it.occurrence.color }?.distinct()?.take(max)?.takeIf { it.isNotEmpty() }?.let { d to it }
    }.toMap()

/** As [monthDays], from the index. */
fun monthDaysOf(index: DayIndex, month: LocalDate, today: LocalDate): List<AgendaDay> {
    val first = month.withDayOfMonth(1)
    return (0 until month.lengthOfMonth()).mapNotNull { i ->
        val d = first.plusDays(i.toLong())
        index[d]?.takeIf { it.entries.isNotEmpty() || d == today } ?: if (d == today) AgendaDay(d, emptyList()) else null
    }
}
