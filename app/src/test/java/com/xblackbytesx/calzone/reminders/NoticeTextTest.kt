package com.xblackbytesx.calzone.reminders

import android.content.Context
import com.xblackbytesx.calzone.model.Due
import com.xblackbytesx.calzone.model.Occurrence
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What a reminder says, in the phone's words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoticeTextTest {
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now = Instant.parse("2026-11-11T14:30:00Z")

    private fun due(start: String, end: String, place: String? = null, allDay: Boolean = false) =
        Due(0, Occurrence(collection = "c", name = "e.ics", title = "Swim", location = place, start = start, end = end, allDay = allDay))

    @Test
    @Config(qualifiers = "en-rUS")
    fun englishTwelveHour() {
        assertEquals("4:00 PM to 5:00 PM · Sports hall", Notices.text(context, due("2026-11-11T16:00:00", "2026-11-11T17:00:00", "Sports hall"), zone, now))
        assertEquals("Tomorrow, 9:00 AM to 9:30 AM", Notices.text(context, due("2026-11-12T09:00:00", "2026-11-12T09:30:00"), zone, now))
        assertEquals("Tomorrow, all day", Notices.text(context, due("2026-11-12", "2026-11-13", allDay = true), zone, now))
    }
}
