package com.xblackbytesx.calzone.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.UserManager
import android.text.format.DateFormat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.xblackbytesx.calzone.MainActivity
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.calendar.Formats
import com.xblackbytesx.calzone.calendar.Intents
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Due
import com.xblackbytesx.calzone.prefs.PrefsStore
import com.xblackbytesx.calzone.ui.parseColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The times kept where Android can read them before the first unlock
 * (device-protected storage, decision 51): times only, never titles. Works
 * from a plain context, before Hilt or the store can be used.
 */
internal class Times(context: Context) {
    private val p = context.createDeviceProtectedStorageContext().getSharedPreferences("reminder_times", Context.MODE_PRIVATE)

    var last: Long?
        get() = p.getLong("last", -1).takeIf { it >= 0 }
        set(v) = p.edit { if (v == null) remove("last") else putLong("last", v) }

    /** Before the first unlock: shown as "unlock to see it" up to here. */
    var lockedUpTo: Long
        get() = p.getLong("locked_up_to", 0)
        set(v) = p.edit { putLong("locked_up_to", v) }

    /** The next due times (reminders and snoozes), soonest first. */
    var times: List<Long>
        get() = p.getString("times", "")!!.split(',').mapNotNull { it.toLongOrNull() }
        set(v) = p.edit { putString("times", v.joinToString(",")) }

    fun clear() = p.edit { clear() }
}

/** Snoozed reminders: in the app's own (credential-protected) storage, as they carry titles. */
private class Snoozes(context: Context) {
    private val p = context.getSharedPreferences("reminder_snoozes", Context.MODE_PRIVATE)
    private val ser = ListSerializer(Due.serializer())

    /** Each with `fire` set to when the snooze ends. */
    var all: List<Due>
        get() = p.getString("snoozed", null)?.let { runCatching { CzJson.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()
        set(v) = p.edit { putString("snoozed", CzJson.encodeToString(ser, v)) }
}

/** The one alarm (decision 51): `setExactAndAllowWhileIdle`, replaced each time. */
internal object Alarm {
    private fun intent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.RING),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // USE_EXACT_ALARM (declared) is granted to calendar apps; lint knows only
    // SCHEDULE_EXACT_ALARM. canScheduleExactAlarms() is checked before use.
    @SuppressLint("MissingPermission")
    fun set(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pi = intent(context)
        if (at == null) {
            am.cancel(pi)
            return
        }
        // USE_EXACT_ALARM is granted to calendar apps; without it, as close as Android allows.
        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at * 1000, pi)
        }
    }

    /** Before the first unlock: the next time from device-protected storage. */
    fun fromTimes(context: Context, after: Long) {
        set(context, Timing.next(Times(context).times, after))
    }
}

/** The reminders' notifications: one per occurrence, with Snooze and Open. */
internal object Notices {
    const val CHANNEL = "reminders"
    private const val LOCKED_ID = 1

    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun channel(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.reminders_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.reminders_channel_hint)
                },
            )
        }
        return nm
    }

    fun idOf(d: Due): Int = "${d.occurrence.collection}/${d.occurrence.name}/${d.occurrence.instance}".hashCode().let { if (it == LOCKED_ID) 2 else it }

    /** Opens the event in the app (the notification, and its Open button). */
    private fun open(context: Context, d: Due): PendingIntent = PendingIntent.getActivity(
        context,
        idOf(d),
        Intent(context, MainActivity::class.java)
            .setAction(Intents.OPEN_EVENT)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Intents.EXTRA_COLLECTION, d.occurrence.collection)
            .putExtra(Intents.EXTRA_NAME, d.occurrence.name)
            .putExtra(Intents.EXTRA_INSTANCE, d.occurrence.instance)
            .putExtra(Intents.EXTRA_NOTICE, idOf(d)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun snooze(context: Context, d: Due): PendingIntent = PendingIntent.getBroadcast(
        context,
        idOf(d),
        Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.SNOOZE)
            .putExtra(ReminderReceiver.EXTRA_DUE, CzJson.encodeToString(Due.serializer(), d)),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** "16:00 to 17:00 · Sports hall", "Tomorrow, all day", in the phone's words. */
    fun text(context: Context, d: Due, zone: ZoneId, now: Instant): String {
        val f = Formats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
        val o = d.occurrence
        val today = now.atZone(zone).toLocalDate()
        val day = { date: LocalDate ->
            when (date) {
                today -> context.getString(R.string.agenda_today)
                today.plusDays(1) -> context.getString(R.string.agenda_tomorrow)
                else -> f.weekday(date, today)
            }
        }
        val whenText = if (o.allDay) {
            val date = runCatching { LocalDate.parse(o.start.take(10)) }.getOrDefault(today)
            context.getString(R.string.all_day_on, day(date))
        } else {
            val s = runCatching { LocalDateTime.parse(o.start) }.getOrNull()
            val e = runCatching { LocalDateTime.parse(o.end) }.getOrNull()
            when {
                s == null -> ""
                s.toLocalDate() == today && e != null && e.toLocalDate() == today ->
                    context.getString(R.string.range, f.time(s.toLocalTime()), f.time(e.toLocalTime()))
                e != null && e.toLocalDate() == s.toLocalDate() ->
                    context.getString(R.string.day_with_times, day(s.toLocalDate()), f.time(s.toLocalTime()), f.time(e.toLocalTime()))
                else -> context.getString(R.string.day_with_time, day(s.toLocalDate()), f.time(s.toLocalTime()))
            }
        }
        return listOfNotNull(whenText.takeIf { it.isNotBlank() }, o.location?.takeIf { it.isNotBlank() }).joinToString(" · ")
    }

    fun post(context: Context, d: Due) {
        if (!allowed(context)) return
        val nm = channel(context)
        val o = d.occurrence
        val title = o.title.ifBlank { context.getString(R.string.untitled) }
        val text = text(context, d, ZoneId.systemDefault(), Instant.now())
        // On the lock screen, without content unless the person allows it.
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_public))
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setColor((parseColor(o.color) ?: Color(0xFF3A7BD5)).toArgb())
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setWhen(o.at * 1000)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setContentIntent(open(context, d))
            .addAction(0, context.getString(R.string.snooze), snooze(context, d))
            .addAction(0, context.getString(R.string.open), open(context, d))
            .build()
        nm.notify(idOf(d), n)
    }

    /** Before the first unlock: "a reminder at 09:00; unlock to see it" (no content can be read yet). */
    fun postLocked(context: Context, times: List<Long>) {
        if (!allowed(context) || times.isEmpty()) return
        val nm = channel(context)
        val f = Formats(context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))
        val zone = ZoneId.systemDefault()
        val at = times.distinct().sorted().joinToString(", ") { f.time(Instant.ofEpochSecond(it).atZone(zone).toLocalTime()) }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_locked_title, at))
            .setContentText(context.getString(R.string.reminder_locked_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(LOCKED_ID, n)
    }

    fun cancelLocked(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(LOCKED_ID)

    fun cancel(context: Context, id: Int) = context.getSystemService(NotificationManager::class.java).cancel(id)

    fun cancelAll(context: Context) = context.getSystemService(NotificationManager::class.java).cancelAll()
}

fun unlocked(context: Context): Boolean = context.getSystemService(UserManager::class.java).isUserUnlocked

/**
 * CalZone's own reminders (decision 51 in calzone-server, decision 32 here):
 * one exact alarm for the next due moment, from the core's occurrences and
 * my reminders, set again after every sync, edit, boot, clock or zone change
 * and update.
 */
class Reminders(private val context: Context, private val core: Core, private val prefs: PrefsStore) {
    private val times = Times(context)
    private val snoozes = Snoozes(context)
    private val lock = Mutex()

    /** The calendars whose reminders do not ring here: those this phone does not show. */
    private suspend fun hidden(): List<String> {
        val p = prefs.prefs.first()
        return core.collections().filter { it.info.isCalendar && !p.shows(it.info.id, it.info.mine.hidden) }.map { it.info.id }
    }

    private fun now(): Long = System.currentTimeMillis() / 1000

    /** Ring whatever is due, then set the next alarm. */
    suspend fun ring() = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val now = now()
                val last = Timing.last(times.last, now)
                val zone = ZoneId.systemDefault().id
                val (from, to) = Timing.window(last, now)
                val due = core.reminders(from, to, zone, hidden()).filter { Timing.worthRinging(it, now) }
                val (snoozedNow, snoozedLater) = snoozes.all.partition { it.fire <= now }
                Notices.cancelLocked(context)
                // One notification per occurrence: its latest reminder replaces an earlier one.
                (due + snoozedNow).forEach { Notices.post(context, it) }
                snoozes.all = snoozedLater
                times.last = now
                times.lockedUpTo = now
                schedule(now)
            }.onFailure { Log.w(TAG, "ring: ${it.javaClass.simpleName}") }
        }
    }

    /** Set the next alarm (after a sync, an edit, or a change to what this phone shows). */
    suspend fun reschedule() = lock.withLock {
        withContext(Dispatchers.IO) {
            runCatching { schedule(now()) }.onFailure { Log.w(TAG, "schedule: ${it.javaClass.simpleName}") }
        }
    }

    private suspend fun schedule(now: Long) {
        val last = Timing.last(times.last, now)
        if (times.last == null) times.last = last
        val from = Timing.scheduleFrom(last, now)
        val coming = core.reminders(from, now + Timing.AHEAD, ZoneId.systemDefault().id, hidden()).map { it.fire } +
            snoozes.all.map { it.fire }
        val sorted = coming.distinct().sorted().take(64)
        times.times = sorted
        Alarm.set(context, sorted.firstOrNull())
    }

    /** Snooze one reminder: it rings again in 10 minutes. */
    suspend fun snooze(d: Due) {
        lock.withLock { snoozes.all = snoozes.all.filterNot { Notices.idOf(it) == Notices.idOf(d) } + d.copy(fire = now() + Timing.SNOOZE) }
        Notices.cancel(context, Notices.idOf(d))
        reschedule()
    }

    /** Reminders set to ring in the coming weeks (for asking the notification permission). */
    fun anyComing(): Boolean = times.times.isNotEmpty()

    /** Signed out: nothing rings any more. */
    suspend fun forget() = lock.withLock {
        Alarm.set(context, null)
        times.clear()
        snoozes.all = emptyList()
        Notices.cancelAll(context)
    }

    companion object {
        const val TAG = "CalZoneReminders"
    }
}
