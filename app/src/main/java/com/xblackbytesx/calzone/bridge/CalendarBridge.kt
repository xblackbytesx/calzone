package com.xblackbytesx.calzone.bridge

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.core.CoreError
import com.xblackbytesx.calzone.model.Collection
import com.xblackbytesx.calzone.model.EventRow
import com.xblackbytesx.calzone.prefs.Prefs
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.flow.first
import android.accounts.Account as AndroidAccount

private val Context.bridgeStore by preferencesDataStore("bridge")

/**
 * The calendar bridge (decision 49 in calzone-server; decisions 13 to 16
 * here): CalZone's store mirrored into Android's calendar, so Fossify,
 * widgets and every other calendar app show it, and their edits come back
 * as pending edits.
 *
 * - [outbound] before a sync: every dirty or deleted row in our calendars
 *   becomes an edit through the core, which compares it with what we wrote
 *   by meaning, so only real changes go up.
 * - [begin] after a sync: our calendars as the person sees them (their
 *   own color and name, read-only where they may only read), and every
 *   object the server changed rewritten from the store. A row another app
 *   is editing right now (still dirty) is left alone until the next run.
 *
 * Writes go through the sync-adapter URIs and select by our calendar ids
 * only (decision 45: account columns exist only in the read view).
 */
class CalendarBridge(private val context: Context, private val core: Core) : Mirror {
    private val cr: ContentResolver = context.contentResolver
    private val versionKey = intPreferencesKey("version")
    private val zoneKey = stringPreferencesKey("zone")

    override fun permitted(): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun zone(): String = TimeZone.getDefault().id

    private fun Uri.asAdapter(a: AndroidAccount): Uri = buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, a.name)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, a.type)
        .build()

    // --- calendars ---

    /** Our calendars: Android id to collection id. */
    private fun calendars(a: AndroidAccount): Map<Long, String> {
        val out = mutableMapOf<Long, String>()
        cr.query(
            Calendars.CONTENT_URI.asAdapter(a),
            arrayOf(Calendars._ID, Calendars._SYNC_ID),
            "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?",
            arrayOf(a.name, a.type),
            null,
        )?.use { c -> while (c.moveToNext()) c.getString(1)?.let { out[c.getLong(0)] = it } }
        return out
    }

    /**
     * Make our calendars match the calendar collections this phone shows.
     * Returns collection id to Android id, and the collections that just got
     * a calendar (they are mirrored in full).
     */
    private fun ensureCalendars(a: AndroidAccount, prefs: Prefs): Pair<Map<String, Long>, Set<String>> {
        val existing = calendars(a)
        val wanted = core.collections().map { it.info }.filter { it.isCalendar && prefs.shows(it.id, it.mine.hidden) }
        val byCollection = existing.entries.associate { (id, coll) -> coll to id }.toMutableMap()
        val fresh = mutableSetOf<String>()
        for (c in wanted) {
            val values = calendarValues(c, a)
            val id = byCollection[c.id]
            if (id != null) {
                cr.update(ContentUris.calendar(id).asAdapter(a), values, null, null)
            } else {
                cr.insert(Calendars.CONTENT_URI.asAdapter(a), values)?.let { uri ->
                    byCollection[c.id] = uri.lastPathSegment!!.toLong()
                    fresh += c.id
                }
            }
        }
        val keep = wanted.map { it.id }.toSet()
        for ((id, coll) in existing) {
            if (coll !in keep) {
                // Its events go with it; dirty ones were read just before.
                cr.delete(ContentUris.calendar(id).asAdapter(a), null, null)
                byCollection.remove(coll)
            }
        }
        return byCollection to fresh
    }

    private fun calendarValues(c: Collection, a: AndroidAccount) = ContentValues().apply {
        put(Calendars.ACCOUNT_NAME, a.name)
        put(Calendars.ACCOUNT_TYPE, a.type)
        put(Calendars._SYNC_ID, c.id)
        put(Calendars.NAME, c.id)
        put(Calendars.CALENDAR_DISPLAY_NAME, c.label)
        put(Calendars.CALENDAR_COLOR, colorInt(c.shownColor))
        // Read-only where the person may only read, or it is a subscribed feed.
        put(Calendars.CALENDAR_ACCESS_LEVEL, if (c.readOnly) Calendars.CAL_ACCESS_READ else Calendars.CAL_ACCESS_OWNER)
        put(Calendars.OWNER_ACCOUNT, a.name)
        put(Calendars.VISIBLE, 1)
        put(Calendars.SYNC_EVENTS, 1)
        put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        put(Calendars.ALLOWED_REMINDERS, "${Reminders.METHOD_DEFAULT},${Reminders.METHOD_ALERT}")
        put(Calendars.ALLOWED_AVAILABILITY, "${Events.AVAILABILITY_BUSY},${Events.AVAILABILITY_FREE}")
        put(Calendars.MAX_REMINDERS, 5)
        put(Calendars.CAN_ORGANIZER_RESPOND, 0)
    }

    // --- outbound: Android's edits into the store ---

    private class Dirty(
        val id: Long,
        val calendar: Long,
        val syncId: String?,
        /** The collection the bridge last wrote it into. */
        val writtenTo: String?,
        val originalId: Long?,
        val deleted: Boolean,
        val row: EventRow,
    )

    /** Read every dirty or deleted row in our calendars into pending edits. */
    override fun outbound(a: AndroidAccount, prefs: Prefs) {
        val calendars = calendars(a)
        if (calendars.isEmpty()) return
        val zone = zone()
        val dirty = mutableListOf<Dirty>()
        cr.query(
            Events.CONTENT_URI.asAdapter(a),
            null,
            "${Events.CALENDAR_ID} IN (${calendars.keys.joinToString(",")}) AND (${Events.DIRTY} = 1 OR ${Events.DELETED} = 1)",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.long(Events._ID)!!
                dirty += Dirty(
                    id = id,
                    calendar = c.long(Events.CALENDAR_ID)!!,
                    syncId = c.str(Events._SYNC_ID),
                    writtenTo = c.str(Events.SYNC_DATA1),
                    originalId = c.long(Events.ORIGINAL_ID),
                    deleted = c.long(Events.DELETED) == 1L,
                    row = rowOf(c, reminders(a, id)),
                )
            }
        }
        // The series first, so a new exception finds its series' name.
        for (d in dirty.sortedBy { it.originalId != null }) {
            val collection = calendars[d.calendar] ?: continue
            try {
                if (d.originalId == null) series(a, d, collection, zone) else exception(a, d, collection, zone)
            } catch (e: CoreError) {
                // Left dirty: the next run tries again. Logged without content.
                Log.w(TAG, "an edit from Android was not taken: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun series(a: AndroidAccount, d: Dirty, collection: String, zone: String) {
        val uri = ContentUris.event(d.id).asAdapter(a)
        when {
            d.deleted -> {
                d.syncId?.let { core.deleteFromAndroid(d.writtenTo ?: collection, it, null, zone) }
                cr.delete(uri, null, null)
            }
            d.syncId == null -> {
                val uid = UUID.randomUUID().toString()
                val name = core.createFromAndroidRow(collection, d.row, zone, uid, context.getString(R.string.untitled))
                cr.update(uri, ContentValues().apply {
                    put(Events._SYNC_ID, name)
                    put(Events.SYNC_DATA1, collection)
                    put(Events.UID_2445, uid)
                    put(Events.DIRTY, 0)
                }, null, null)
            }
            d.writtenTo != null && d.writtenTo != collection -> {
                core.moveFromAndroid(d.writtenTo, collection, d.syncId, d.row, zone)
                cr.update(uri, ContentValues().apply {
                    put(Events.SYNC_DATA1, collection)
                    put(Events.DIRTY, 0)
                }, null, null)
            }
            else -> {
                core.applyAndroidRow(collection, d.syncId, d.row, zone)
                cr.update(uri, ContentValues().apply { put(Events.DIRTY, 0) }, null, null)
            }
        }
    }

    private fun exception(a: AndroidAccount, d: Dirty, collection: String, zone: String) {
        val uri = ContentUris.event(d.id).asAdapter(a)
        val original = d.row.originalInstance
        val name = seriesName(a, d.originalId!!)
        if (name == null || original == null) {
            // Its series is gone or not ours yet: nothing to attach it to.
            if (d.deleted) cr.delete(uri, null, null)
            return
        }
        if (d.deleted) {
            core.deleteFromAndroid(collection, name, original, zone)
            cr.delete(uri, null, null)
            return
        }
        core.applyAndroidRow(collection, name, d.row, zone)
        cr.update(uri, ContentValues().apply {
            put(Events.ORIGINAL_SYNC_ID, name)
            put(Events.DIRTY, 0)
        }, null, null)
    }

    private fun seriesName(a: AndroidAccount, id: Long): String? =
        cr.query(ContentUris.event(id).asAdapter(a), arrayOf(Events._SYNC_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun reminders(a: AndroidAccount, event: Long): List<Long> {
        val out = mutableListOf<Long>()
        cr.query(
            Reminders.CONTENT_URI.asAdapter(a),
            arrayOf(Reminders.MINUTES, Reminders.METHOD),
            "${Reminders.EVENT_ID} = ?",
            arrayOf(event.toString()),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val minutes = c.getLong(0)
                val method = c.getInt(1)
                if (minutes >= 0 && (method == Reminders.METHOD_ALERT || method == Reminders.METHOD_DEFAULT)) out += minutes
            }
        }
        return out.distinct().sorted()
    }

    // --- inbound: the store into Android ---

    /**
     * Calendars, then (through the [Pass]) every change from the server
     * since the last time; everything when a calendar is new, the mapping
     * changed, the phone moved to another zone (floating times are shown in
     * the phone's zone), or the bridge was without access.
     */
    override suspend fun begin(a: AndroidAccount, prefs: Prefs): Pass {
        val (calendars, fresh) = ensureCalendars(a, prefs)
        val zone = zone()
        val state = context.bridgeStore.data.first()
        val everything = state[versionKey] != VERSION || state[zoneKey] != zone
        val full = if (everything) calendars.keys else fresh
        for (collection in full) {
            val calendar = calendars[collection] ?: continue
            mirror(a, calendar, collection, zone)
        }
        return object : Pass {
            override fun write(collection: String, name: String) {
                val calendar = calendars[collection] ?: return
                if (collection in full) return
                try {
                    writeObject(a, calendar, collection, name, zone)
                } catch (e: CoreError) {
                    Log.w(TAG, "a change was not written to Android: ${e.javaClass.simpleName}")
                }
            }

            override suspend fun finish() {
                context.bridgeStore.edit {
                    it[versionKey] = VERSION
                    it[zoneKey] = zone
                }
            }
        }
    }

    override suspend fun forget() {
        context.bridgeStore.edit { it.remove(versionKey) }
    }

    /** One calendar in full: every stored object written, rows of objects the store no longer has removed. */
    private fun mirror(a: AndroidAccount, calendar: Long, collection: String, zone: String) {
        val names = core.objects(collection).map { it.name }.toSet()
        for (name in names) {
            try {
                writeObject(a, calendar, collection, name, zone)
            } catch (e: CoreError) {
                Log.w(TAG, "an event was not written to Android: ${e.javaClass.simpleName}")
            }
        }
        val stale = mutableListOf<Pair<Long, String>>()
        cr.query(
            Events.CONTENT_URI.asAdapter(a),
            arrayOf(Events._ID, Events._SYNC_ID),
            "${Events.CALENDAR_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL AND ${Events._SYNC_ID} IS NOT NULL AND ${Events.DIRTY} = 0",
            arrayOf(calendar.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) if (c.getString(1) !in names) stale += c.getLong(0) to c.getString(1) }
        for ((_, name) in stale) remove(a, calendar, name)
    }

    /** The main row of `name` in `calendar`: its id and whether another app is editing it. */
    private fun mainRow(a: AndroidAccount, calendar: Long, name: String): Pair<Long, Boolean>? =
        cr.query(
            Events.CONTENT_URI.asAdapter(a),
            arrayOf(Events._ID, Events.DIRTY),
            "${Events.CALENDAR_ID} = ? AND ${Events._SYNC_ID} = ? AND ${Events.ORIGINAL_ID} IS NULL",
            arrayOf(calendar.toString(), name),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) to (c.getLong(1) == 1L) else null }

    private fun remove(a: AndroidAccount, calendar: Long, name: String) {
        val (id, dirty) = mainRow(a, calendar, name) ?: return
        if (dirty) return
        cr.delete(Events.CONTENT_URI.asAdapter(a), "${Events.ORIGINAL_ID} = ?", arrayOf(id.toString()))
        cr.delete(ContentUris.event(id).asAdapter(a), null, null)
    }

    /** Write one stored object's rows: update in place, insert what is new, drop what is gone. */
    private fun writeObject(a: AndroidAccount, calendar: Long, collection: String, name: String, zone: String) {
        val rows = core.eventRows(collection, name, zone) ?: return remove(a, calendar, name)
        val existing = mainRow(a, calendar, name)
        if (existing?.second == true) return
        val values = columns(rows.main).toContentValues().apply {
            put(Events.CALENDAR_ID, calendar)
            put(Events._SYNC_ID, name)
            put(Events.SYNC_DATA1, collection)
            put(Events.DIRTY, 0)
        }
        val mainId = if (existing != null) {
            cr.update(ContentUris.event(existing.first).asAdapter(a), values, "${Events.DIRTY} = 0", null)
            existing.first
        } else {
            cr.insert(Events.CONTENT_URI.asAdapter(a), values)?.lastPathSegment?.toLong() ?: return
        }
        setReminders(a, mainId, rows.main.reminders)

        val old = mutableMapOf<Long, Pair<Long, Boolean>>()
        cr.query(
            Events.CONTENT_URI.asAdapter(a),
            arrayOf(Events._ID, Events.ORIGINAL_INSTANCE_TIME, Events.DIRTY),
            "${Events.ORIGINAL_ID} = ?",
            arrayOf(mainId.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) old[c.getLong(1)] = c.getLong(0) to (c.getLong(2) == 1L) }
        for (x in rows.exceptions) {
            val at = x.originalInstance ?: continue
            val v = columns(x).toContentValues().apply {
                put(Events.CALENDAR_ID, calendar)
                put(Events.ORIGINAL_ID, mainId)
                put(Events.ORIGINAL_SYNC_ID, name)
                put(Events.SYNC_DATA1, collection)
                put(Events.DIRTY, 0)
            }
            val prior = old.remove(at)
            val id = when {
                prior == null -> cr.insert(Events.CONTENT_URI.asAdapter(a), v)?.lastPathSegment?.toLong()
                prior.second -> null
                else -> prior.first.also { cr.update(ContentUris.event(it).asAdapter(a), v, "${Events.DIRTY} = 0", null) }
            }
            id?.let { setReminders(a, it, x.reminders) }
        }
        // Exceptions the object no longer has (a canceled occurrence became an EXDATE).
        for ((_, prior) in old) if (!prior.second) cr.delete(ContentUris.event(prior.first).asAdapter(a), null, null)
    }

    private fun setReminders(a: AndroidAccount, event: Long, minutes: List<Long>) {
        cr.delete(Reminders.CONTENT_URI.asAdapter(a), "${Reminders.EVENT_ID} = ?", arrayOf(event.toString()))
        for (m in minutes) {
            cr.insert(Reminders.CONTENT_URI.asAdapter(a), ContentValues().apply {
                put(Reminders.EVENT_ID, event)
                put(Reminders.MINUTES, m)
                put(Reminders.METHOD, Reminders.METHOD_ALERT)
            })
        }
    }

    companion object {
        private const val TAG = "CalZoneBridge"

        /** Bump when the mapping changes: every calendar is written again. */
        const val VERSION = 1

        val PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
    }
}

private object ContentUris {
    fun calendar(id: Long): Uri = android.content.ContentUris.withAppendedId(Calendars.CONTENT_URI, id)
    fun event(id: Long): Uri = android.content.ContentUris.withAppendedId(Events.CONTENT_URI, id)
}

private fun Cursor.str(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)

private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong)

/** An Events row as the core reads it. */
private fun rowOf(c: Cursor, reminders: List<Long>) = EventRow(
    title = c.str(Events.TITLE),
    location = c.str(Events.EVENT_LOCATION),
    description = c.str(Events.DESCRIPTION),
    dtstart = c.long(Events.DTSTART) ?: 0,
    dtend = c.long(Events.DTEND),
    duration = c.str(Events.DURATION),
    allDay = c.long(Events.ALL_DAY) == 1L,
    timezone = c.str(Events.EVENT_TIMEZONE) ?: "UTC",
    endTimezone = c.str(Events.EVENT_END_TIMEZONE),
    rrule = c.str(Events.RRULE),
    rdate = c.str(Events.RDATE),
    exrule = c.str(Events.EXRULE),
    exdate = c.str(Events.EXDATE),
    status = c.long(Events.STATUS)?.toInt(),
    availability = c.long(Events.AVAILABILITY)?.toInt() ?: 0,
    accessLevel = c.long(Events.ACCESS_LEVEL)?.toInt() ?: 0,
    uid = c.str(Events.UID_2445),
    originalInstance = c.long(Events.ORIGINAL_INSTANCE_TIME),
    originalAllDay = c.long(Events.ORIGINAL_ALL_DAY)?.let { it == 1L },
    reminders = reminders,
)

private fun Map<String, Any?>.toContentValues() = ContentValues().apply {
    for ((k, v) in this@toContentValues) {
        when (v) {
            null -> putNull(k)
            is String -> put(k, v)
            is Long -> put(k, v)
            is Int -> put(k, v)
            is Boolean -> put(k, if (v) 1 else 0)
            else -> error("unexpected column type for $k")
        }
    }
}
