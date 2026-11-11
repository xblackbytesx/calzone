package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.EventView
import com.xblackbytesx.calzone.model.QuickDraft
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How a repeat stops: never, on a day, or after a number of times. */
enum class RepeatEnd { NEVER, UNTIL, COUNT }

/** The reminders the editor offers (the core's `ALARM_PRESETS`), minutes before the start. */
val ALARM_PRESETS = listOf(0L, 5L, 15L, 30L, 60L, 1440L)

/** Default times when an all-day event becomes a timed one. */
private val NINE: LocalTime = LocalTime.of(9, 0)
private val TEN: LocalTime = LocalTime.of(10, 0)

/**
 * The editor's form, as the web's event panel has it (decision 30). Times
 * are kept while "all day" is on, so switching it off again brings them
 * back. `endDate` of an all-day event is its last day, as people say it.
 */
data class Draft(
    val collection: String,
    val title: String = "",
    val allDay: Boolean = false,
    val startDate: LocalDate,
    val startTime: LocalTime? = null,
    val endDate: LocalDate = startDate,
    val endTime: LocalTime? = null,
    /** `none`, `daily`, `weekly`, `monthly`, `yearly`, or `custom` (set elsewhere, kept unless changed). */
    val repeat: String = "none",
    val repeatEnd: RepeatEnd = RepeatEnd.NEVER,
    val until: LocalDate? = null,
    val count: Int? = null,
    val location: String = "",
    val notes: String = "",
    val alarms: List<Long> = emptyList(),
) {
    val start: LocalDateTime get() = startDate.atTime(startTime ?: NINE)
    val end: LocalDateTime get() = endDate.atTime(endTime ?: startTime?.plusHours(1) ?: TEN)

    /**
     * A new start; the end moves with it, so the event keeps its length (as
     * every calendar app does).
     */
    fun withStart(date: LocalDate, time: LocalTime?): Draft {
        if (allDay) {
            val days = ChronoUnit.DAYS.between(startDate, endDate).coerceAtLeast(0)
            return copy(startDate = date, startTime = time ?: startTime, endDate = date.plusDays(days))
        }
        val length = Duration.between(start, end).let { if (it.isNegative) Duration.ofHours(1) else it }
        val s = date.atTime(time ?: startTime ?: NINE)
        val e = s.plus(length)
        return copy(startDate = s.toLocalDate(), startTime = s.toLocalTime(), endDate = e.toLocalDate(), endTime = e.toLocalTime())
    }

    /** A new end; one before the start is taken as the start's day (or an hour after it). */
    fun withEnd(date: LocalDate, time: LocalTime?): Draft {
        if (allDay) return copy(endDate = if (date < startDate) startDate else date, endTime = time ?: endTime)
        val e = date.atTime(time ?: endTime ?: TEN)
        val fixed = if (e < start) start.plusHours(1) else e
        return copy(endDate = fixed.toLocalDate(), endTime = fixed.toLocalTime())
    }

    /** Quick add's reading put in the form: the title, and the time when it found one. */
    fun filled(q: QuickDraft): Draft {
        val withTitle = copy(title = q.title)
        val s = q.start ?: return withTitle
        val e = q.end ?: s
        return if (q.allDay) {
            val first = LocalDate.parse(s.take(10))
            val last = LocalDate.parse(e.take(10)).minusDays(1).let { if (it < first) first else it }
            withTitle.copy(allDay = true, startDate = first, endDate = last)
        } else {
            val a = LocalDateTime.parse(s)
            val b = LocalDateTime.parse(e).let { if (it < a) a.plusHours(1) else it }
            withTitle.copy(allDay = false, startDate = a.toLocalDate(), startTime = a.toLocalTime(), endDate = b.toLocalDate(), endTime = b.toLocalTime())
        }
    }
}

/** The form for an event as the sheet showed it. */
fun draftOf(e: EventView): Draft {
    val sd = LocalDate.parse(e.startDate)
    val ed = runCatching { LocalDate.parse(e.endDate) }.getOrDefault(sd)
    val st = e.startTime.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val et = e.endTime.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    val until = e.repeatUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    return Draft(
        collection = e.collection,
        title = e.title,
        allDay = e.allDay,
        startDate = sd,
        startTime = st,
        endDate = if (ed < sd) sd else ed,
        endTime = et,
        repeat = e.repeat,
        repeatEnd = when {
            until != null -> RepeatEnd.UNTIL
            e.repeatCount != null -> RepeatEnd.COUNT
            else -> RepeatEnd.NEVER
        },
        until = until,
        count = e.repeatCount,
        location = e.location,
        notes = e.description,
        alarms = e.alarms.sorted(),
    )
}

/** A new event's form: from `start` for an hour, or all day. */
fun newDraft(collection: String, start: LocalDateTime, allDay: Boolean, alarms: List<Long>): Draft {
    val end = start.plusHours(1)
    return Draft(
        collection = collection,
        allDay = allDay,
        startDate = start.toLocalDate(),
        startTime = if (allDay) null else start.toLocalTime(),
        endDate = if (allDay) start.toLocalDate() else end.toLocalDate(),
        endTime = if (allDay) null else end.toLocalTime(),
        alarms = alarms,
    )
}

/** Where a new event starts: the next whole hour on today, else 9:00 on the day in sight. */
fun newStart(day: LocalDate, today: LocalDate, now: LocalTime): LocalDateTime =
    if (day == today) day.atTime(now.withMinute(0).withSecond(0).withNano(0)).plusHours(1) else day.atTime(NINE)

private fun wallText(t: LocalDateTime): String = t.truncatedTo(ChronoUnit.MINUTES).toString()

/**
 * The input JSON for the core: only what changed (a new event: everything),
 * as the web's `changeBody` sends it. An empty object means nothing changed.
 */
fun changeBody(v: Draft, initial: Draft, isNew: Boolean): JsonObject = buildJsonObject {
    fun changed(f: (Draft) -> Any?) = isNew || f(v) != f(initial)
    if (changed { it.title }) put("summary", v.title.trim())
    if (changed { it.location }) put("location", v.location)
    if (changed { it.notes }) put("description", v.notes)
    val timeChanged = changed { it.allDay } || changed { it.startDate } || changed { it.endDate } ||
        (!v.allDay && (changed { it.startTime } || changed { it.endTime }))
    if (timeChanged) {
        if (v.allDay) {
            put("start", v.startDate.toString())
            // Shown as the last day; stored as the day after.
            put("end", (if (v.endDate < v.startDate) v.startDate else v.endDate).plusDays(1).toString())
        } else {
            val s = v.start
            val e = v.end.let { if (it <= s) s.plusHours(1) else it }
            put("start", wallText(s))
            put("end", wallText(e))
        }
    }
    val repeatChanged = changed { it.repeat } || changed { it.repeatEnd } || changed { it.until } || changed { it.count }
    if (repeatChanged && v.repeat != "custom") {
        if (v.repeat == "none") {
            put("repeat", JsonNull)
        } else {
            putJsonObject("repeat") {
                put("freq", v.repeat)
                if (v.repeatEnd == RepeatEnd.UNTIL && v.until != null) put("until", v.until.toString())
                if (v.repeatEnd == RepeatEnd.COUNT && v.count != null && v.count > 0) put("count", v.count.coerceAtMost(1000))
            }
        }
    }
    if (changed { it.alarms.sorted() }) putJsonArray("alarms") { v.alarms.distinct().sorted().forEach { add(it) } }
}

/** Only my reminders changed: they cover every occurrence, so no scope question (as on the web). */
fun remindersOnly(body: JsonObject): Boolean = body.keys == setOf("alarms")
