package com.xblackbytesx.calzone.calendar

import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What another app (or a widget, or a notification) asks of the calendar (decision 34). */
sealed interface Request {
    /** An event of CalZone's: its sheet, or straight into the editor. */
    data class Open(val target: Target, val edit: Boolean = false) : Request

    /** A row of Android's calendar (another app's "open" or "edit"), mapped back to CalZone's event. */
    data class Row(val id: Long, val beginMs: Long?, val edit: Boolean) : Request

    /** "Add to calendar": a new event, filled in with what came along. */
    data class Insert(val prefill: Prefill) : Request

    /** A day to show (a widget's day, another app's "show this time"). */
    data class Day(val date: LocalDate) : Request
}

/** What an "add to calendar" brings (Android's `Events` columns and `EXTRA_EVENT_*`). */
data class Prefill(
    val title: String = "",
    val location: String = "",
    val notes: String = "",
    val beginMs: Long? = null,
    val endMs: Long? = null,
    val allDay: Boolean = false,
)

/** Requests waiting for the calendar screen; it takes each and clears it. */
object CalendarRequests {
    private val _requests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = _requests

    fun send(r: Request) {
        _requests.value = r
    }

    fun taken() {
        _requests.value = null
    }
}

/** The app's own intents (notifications, widgets). */
object Intents {
    const val OPEN_EVENT = "com.xblackbytesx.calzone.OPEN_EVENT"
    const val OPEN_DAY = "com.xblackbytesx.calzone.OPEN_DAY"
    const val NEW_EVENT = "com.xblackbytesx.calzone.NEW_EVENT"
    const val EXTRA_COLLECTION = "collection"
    const val EXTRA_NAME = "name"
    const val EXTRA_INSTANCE = "instance"
    const val EXTRA_DATE = "date"
    const val EXTRA_NOTICE = "notice"
}

private const val AUTHORITY = "com.android.calendar"

/**
 * An intent as a request, or null when it is not one: the app's own, and
 * the ones calendar apps answer (AOSP Calendar's filters): `INSERT` of an
 * event, `VIEW` or `EDIT` of `content://com.android.calendar/events/<id>`,
 * `VIEW` of `content://com.android.calendar/time/<ms>`.
 */
fun requestOf(intent: Intent?, zone: ZoneId = ZoneId.systemDefault()): Request? {
    intent ?: return null
    val data = intent.data
    return when (intent.action) {
        Intents.OPEN_EVENT -> {
            val c = intent.getStringExtra(Intents.EXTRA_COLLECTION) ?: return null
            val n = intent.getStringExtra(Intents.EXTRA_NAME) ?: return null
            Request.Open(Target(c, n, intent.getStringExtra(Intents.EXTRA_INSTANCE)))
        }
        Intents.OPEN_DAY -> intent.getStringExtra(Intents.EXTRA_DATE)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let(Request::Day)
        Intents.NEW_EVENT -> Request.Insert(Prefill())
        Intent.ACTION_INSERT -> {
            val dir = intent.type == "vnd.android.cursor.dir/event" || (data?.authority == AUTHORITY && data.pathSegments.firstOrNull() == "events")
            if (!dir) return null
            fun long(k: String) = intent.getLongExtra(k, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
            Request.Insert(
                Prefill(
                    title = intent.getStringExtra(Events.TITLE).orEmpty(),
                    location = intent.getStringExtra(Events.EVENT_LOCATION).orEmpty(),
                    notes = intent.getStringExtra(Events.DESCRIPTION).orEmpty(),
                    beginMs = long(CalendarContract.EXTRA_EVENT_BEGIN_TIME) ?: long(Events.DTSTART),
                    endMs = long(CalendarContract.EXTRA_EVENT_END_TIME) ?: long(Events.DTEND),
                    allDay = intent.getBooleanExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false) ||
                        intent.getIntExtra(Events.ALL_DAY, 0) == 1,
                ),
            )
        }
        Intent.ACTION_VIEW, Intent.ACTION_EDIT -> {
            if (data?.authority != AUTHORITY) return null
            val seg = data.pathSegments
            when (seg.firstOrNull()) {
                "events" -> seg.getOrNull(1)?.toLongOrNull()?.let { id ->
                    val begin = intent.getLongExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0L).takeIf { it > 0 }
                    Request.Row(id, begin, edit = intent.action == Intent.ACTION_EDIT)
                }
                "time" -> seg.getOrNull(1)?.toLongOrNull()?.let { Request.Day(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
                else -> null
            }
        }
        else -> null
    }
}

/**
 * An "add to calendar" as the editor's form: the begin and end it brought
 * (an hour by default), all-day ones in UTC days as Android sends them.
 */
fun draftFrom(p: Prefill, collection: String, fallbackStart: java.time.LocalDateTime, alarms: List<Long>, zone: ZoneId = ZoneId.systemDefault()): Draft {
    val begin = p.beginMs?.let { Instant.ofEpochMilli(it) }
    val end = p.endMs?.let { Instant.ofEpochMilli(it) }
    val base = if (p.allDay && begin != null) {
        val first = begin.atZone(ZoneOffset.UTC).toLocalDate()
        // Android's all-day end is the day after; shown as the last day.
        val last = end?.atZone(ZoneOffset.UTC)?.toLocalDate()?.minusDays(1)?.takeIf { it >= first } ?: first
        newDraft(collection, first.atStartOfDay(), allDay = true, alarms = alarms).copy(endDate = last)
    } else {
        val s = begin?.atZone(zone)?.toLocalDateTime() ?: fallbackStart
        val d = newDraft(collection, s, allDay = false, alarms = alarms)
        val e = end?.atZone(zone)?.toLocalDateTime()?.takeIf { it > s }
        if (e == null) d else d.copy(endDate = e.toLocalDate(), endTime = e.toLocalTime())
    }
    return base.copy(title = p.title, location = p.location, notes = p.notes)
}
