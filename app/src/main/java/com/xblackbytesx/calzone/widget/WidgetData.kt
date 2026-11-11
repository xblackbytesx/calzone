package com.xblackbytesx.calzone.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.util.Log
import androidx.glance.appwidget.updateAll
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.calendar.DayTime
import com.xblackbytesx.calzone.calendar.Formats
import com.xblackbytesx.calzone.calendar.agendaDays
import com.xblackbytesx.calzone.calendar.dayColors
import com.xblackbytesx.calzone.calendar.monthWeeks
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.model.Occurrence
import com.xblackbytesx.calzone.prefs.PrefsStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntry {
    fun core(): Core
    fun prefs(): PrefsStore
}

/** One line of the agenda widget: a day's heading, or an event. */
sealed interface AgendaLine {
    data class Heading(val text: String, val today: Boolean) : AgendaLine
    data class Entry(val o: Occurrence, val time: String) : AgendaLine
    data class Nothing(val text: String) : AgendaLine
}

/**
 * The agenda widget's days: today and the next `days - 1`, each day with
 * something, and today always; today's timed events that are over are
 * left out.
 */
fun widgetAgenda(items: List<Occurrence>, today: LocalDate, nowSec: Long, days: Long = 14) =
    agendaDays(items, today, today.plusDays(days), today).map { d ->
        if (d.date != today) d else d.copy(entries = d.entries.filter { it.time is DayTime.AllDay || it.occurrence.endAt > nowSec })
    }

/** What the widgets show, read on the IO thread. */
sealed interface WidgetState {
    data object SignedOut : WidgetState
    data object Failed : WidgetState
    data class Ready(val today: LocalDate, val items: List<Occurrence>) : WidgetState
}

internal object WidgetData {
    /** The occurrences in `[from, to)` days in the calendars this phone shows. */
    suspend fun load(context: Context, from: LocalDate, to: LocalDate): WidgetState = runCatching {
        val e = EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntry::class.java)
        val core = e.core()
        if (core.account() == null) return WidgetState.SignedOut
        val prefs = e.prefs().prefs.first()
        val hidden = core.collections().filter { it.info.isCalendar && !prefs.shows(it.info.id, it.info.mine.hidden) }.map { it.info.id }
        val zone = ZoneId.systemDefault()
        val items = core.occurrences(from.atStartOfDay(zone).toEpochSecond(), to.atStartOfDay(zone).toEpochSecond(), zone.id, hidden)
        WidgetState.Ready(LocalDate.now(zone), items)
    }.getOrElse {
        Log.w("CalZoneWidget", "load: ${it.javaClass.simpleName}")
        WidgetState.Failed
    }

    fun formats(context: Context) = Formats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))

    fun dayTitle(context: Context, d: LocalDate, today: LocalDate, f: Formats): String = when (d) {
        today -> context.getString(R.string.agenda_today)
        today.plusDays(1) -> context.getString(R.string.agenda_tomorrow)
        else -> f.weekday(d, today).replaceFirstChar { it.titlecase(f.locale) }
    }

    fun timeText(context: Context, t: DayTime, f: Formats): String = when (t) {
        DayTime.AllDay -> context.getString(R.string.all_day)
        is DayTime.Range -> t.end?.let { context.getString(R.string.range, f.time(t.start), f.time(it)) } ?: f.time(t.start)
        is DayTime.Starts -> f.time(t.start)
        is DayTime.Ends -> context.getString(R.string.until_time, f.time(t.end))
    }

    fun agendaLines(context: Context, s: WidgetState.Ready, nowSec: Long): List<AgendaLine> {
        val f = formats(context)
        return widgetAgenda(s.items, s.today, nowSec).flatMap { d ->
            listOf<AgendaLine>(AgendaLine.Heading(dayTitle(context, d.date, s.today, f), d.date == s.today)) +
                if (d.entries.isEmpty()) {
                    listOf(AgendaLine.Nothing(context.getString(R.string.agenda_nothing)))
                } else {
                    d.entries.map { AgendaLine.Entry(it.occurrence, timeText(context, it.time, f)) }
                }
        }
    }

    /** The month widget's six weeks and the calendars' dots on each day. */
    fun month(s: WidgetState.Ready, first: java.time.DayOfWeek): Pair<List<List<LocalDate>>, Map<LocalDate, List<String>>> {
        val weeks = monthWeeks(s.today, first).let { w -> w + (1..(6 - w.size)).map { n -> w.last().map { it.plusWeeks(n.toLong()) } } }
        return weeks to dayColors(s.items, weeks.flatten())
    }
}

/**
 * Brings both widgets up to date (decision 35): after every sync run and
 * every edit in the app, on a clock or zone change, and just after midnight
 * (an inexact alarm, set again each time).
 */
object Widgets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun update(context: Context) {
        runCatching {
            AgendaWidget().updateAll(context)
            MonthWidget().updateAll(context)
        }.onFailure { Log.w("CalZoneWidget", "update: ${it.javaClass.simpleName}") }
        midnight(context)
    }

    fun updateSoon(context: Context) {
        scope.launch { update(context.applicationContext) }
    }

    private fun midnight(context: Context) {
        val zone = ZoneId.systemDefault()
        val next = LocalDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond() * 1000 + 30_000
        val pi = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, WidgetRefreshReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC, next, pi)
    }
}

/** Midnight: the widgets' today moves on. */
class WidgetRefreshReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Widgets.update(context.applicationContext)
            } finally {
                pending.finish()
            }
        }
    }
}
