package com.xblackbytesx.calzone.bridge

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import com.xblackbytesx.calzone.account.AndroidAccounts

/** A row of Android's calendar that the bridge wrote: CalZone's collection and object, and, for one occurrence's row, its original start. */
data class RowRef(val collection: String, val name: String, val originalMs: Long?)

/**
 * Another app opened an event in Android's calendar (decision 34): find
 * which of CalZone's it is, by the keys the bridge wrote (decision 14).
 * Null when it is not CalZone's, or calendar access is off.
 */
object SystemEvents {
    fun find(context: Context, id: Long): RowRef? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val cr = context.contentResolver
        val ours = AndroidAccounts.type(context)
        val row = cr.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, id),
            arrayOf(Events._SYNC_ID, Events.ORIGINAL_SYNC_ID, Events.ORIGINAL_INSTANCE_TIME, Events.CALENDAR_ID, Events.ACCOUNT_TYPE),
            null,
            null,
            null,
        )?.use { c ->
            if (!c.moveToFirst() || c.getString(4) != ours) return null
            val name = c.getString(0) ?: c.getString(1) ?: return null
            val original = if (c.isNull(2)) null else c.getLong(2)
            Triple(name, original, c.getLong(3))
        } ?: return null
        val collection = cr.query(
            ContentUris.withAppendedId(Calendars.CONTENT_URI, row.third),
            arrayOf(Calendars._SYNC_ID),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return null
        return RowRef(collection, row.first, row.second)
    }
}
