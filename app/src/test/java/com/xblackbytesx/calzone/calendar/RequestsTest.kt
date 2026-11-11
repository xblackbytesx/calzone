package com.xblackbytesx.calzone.calendar

import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Events
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What other apps send calendar apps, read as requests (decision 34). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RequestsTest {
    private val ams = ZoneId.of("Europe/Amsterdam")

    @Test
    fun addToCalendar() {
        val i = Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
            .putExtra(Events.TITLE, "Concert")
            .putExtra(Events.EVENT_LOCATION, "Paradiso")
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_795_000_000_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, 1_795_007_200_000L)
        val r = requestOf(i, ams) as Request.Insert
        assertEquals("Concert", r.prefill.title)
        val d = draftFrom(r.prefill, "1", LocalDateTime.parse("2026-11-11T17:00"), listOf(15), ams)
        assertEquals("UTC 11:06:40 is 12:06:40 in Amsterdam", LocalDateTime.parse("2026-11-18T12:06:40"), d.start)
        assertEquals(LocalDateTime.parse("2026-11-18T14:06:40"), d.end)
        assertEquals(listOf(15L), d.alarms)
        assertEquals("Paradiso", d.location)
    }

    @Test
    fun allDayComesInUtcDays() {
        val p = Prefill(title = "Holiday", beginMs = 1_798_416_000_000L, endMs = 1_798_675_200_000L, allDay = true)
        val d = draftFrom(p, "1", LocalDateTime.parse("2026-11-11T17:00"), emptyList(), ams)
        // 2026-12-28 up to the day after the 30th.
        assertEquals(LocalDate.parse("2026-12-28") to LocalDate.parse("2026-12-30"), d.startDate to d.endDate)
        assertEquals(true, d.allDay)
    }

    @Test
    fun openingAndEditingRowsAndTimes() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/events/42"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, 1_795_000_000_000L)
        assertEquals(Request.Row(42, 1_795_000_000_000L, edit = false), requestOf(view, ams))
        assertEquals(Request.Row(7, null, edit = true), requestOf(Intent(Intent.ACTION_EDIT, Uri.parse("content://com.android.calendar/events/7")), ams))
        assertEquals(
            Request.Day(LocalDate.parse("2026-11-18")),
            requestOf(Intent(Intent.ACTION_VIEW, Uri.parse("content://com.android.calendar/time/1795000000000")), ams),
        )
        assertNull("another provider's row", requestOf(Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/1")), ams))
        assertNull(requestOf(Intent(Intent.ACTION_MAIN), ams))
    }

    @Test
    fun theAppsOwn() {
        val open = Intent(Intents.OPEN_EVENT).putExtra(Intents.EXTRA_COLLECTION, "1").putExtra(Intents.EXTRA_NAME, "swim.ics")
        assertEquals(Request.Open(Target("1", "swim.ics", null)), requestOf(open, ams))
        assertEquals(Request.Day(LocalDate.parse("2026-11-19")), requestOf(Intent(Intents.OPEN_DAY).putExtra(Intents.EXTRA_DATE, "2026-11-19"), ams))
        assertEquals(Request.Insert(Prefill()), requestOf(Intent(Intents.NEW_EVENT), ams))
    }
}
