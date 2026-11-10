package com.xblackbytesx.calzone.bridge

import android.provider.CalendarContract.Events
import com.xblackbytesx.calzone.model.EventRow

/**
 * The `Events` columns for a row (the column names are compile-time
 * constants, so this is pure and tested on the JVM). Every column the
 * bridge owns is written, nulls included, so a value the server removed is
 * removed in Android too.
 */
fun columns(r: EventRow): Map<String, Any?> = linkedMapOf(
    Events.TITLE to r.title,
    Events.EVENT_LOCATION to r.location,
    Events.DESCRIPTION to r.description,
    Events.DTSTART to r.dtstart,
    // Android wants DTEND for a single event and DURATION for a series.
    Events.DTEND to (if (r.duration == null) r.dtend else null),
    Events.DURATION to r.duration,
    Events.ALL_DAY to r.allDay,
    Events.EVENT_TIMEZONE to r.timezone,
    Events.EVENT_END_TIMEZONE to (if (r.duration == null) r.endTimezone else null),
    Events.RRULE to r.rrule,
    Events.RDATE to r.rdate,
    Events.EXRULE to r.exrule,
    Events.EXDATE to r.exdate,
    Events.STATUS to r.status,
    Events.AVAILABILITY to r.availability,
    Events.ACCESS_LEVEL to r.accessLevel,
    Events.UID_2445 to r.uid,
    Events.ORIGINAL_INSTANCE_TIME to r.originalInstance,
    Events.ORIGINAL_ALL_DAY to r.originalAllDay,
    Events.HAS_ALARM to r.reminders.isNotEmpty(),
)

/** `#rrggbb` as Android's ARGB color; the web's accent when there is none. */
fun colorInt(hex: String?): Int {
    val h = hex?.removePrefix("#")?.takeIf { it.length == 6 || it.length == 8 }?.take(6)
    val rgb = h?.toLongOrNull(16) ?: 0x0F766EL
    return (0xFF000000L or rgb).toInt()
}
