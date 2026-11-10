package com.xblackbytesx.calzone.bridge

import android.provider.CalendarContract.Events
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.EventRow
import com.xblackbytesx.calzone.model.EventRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColumnsTest {
    @Test
    fun aSeriesGetsDurationAndASingleEventDtend() {
        val series = EventRow(title = "Swim", dtstart = 1L, dtend = 99L, duration = "P3600S", timezone = "Europe/Amsterdam", rrule = "FREQ=WEEKLY", reminders = listOf(15))
        val c = columns(series)
        assertNull("Android wants DURATION, not DTEND, for a series", c[Events.DTEND])
        assertEquals("P3600S", c[Events.DURATION])
        assertEquals(true, c[Events.HAS_ALARM])
        val single = columns(EventRow(dtstart = 1L, dtend = 2L, endTimezone = "UTC"))
        assertEquals(2L, single[Events.DTEND])
        assertNull(single[Events.DURATION])
        assertTrue("every owned column is written, nulls too", single.containsKey(Events.RRULE) && single.containsKey(Events.EXDATE))
    }

    @Test
    fun colors() {
        assertEquals(0xFF3A7BD5.toInt(), colorInt("#3a7bd5"))
        assertEquals(0xFF3A7BD5.toInt(), colorInt("#3A7BD5FF"))
        assertEquals(0xFF0F766E.toInt(), colorInt(null))
        assertEquals(0xFF0F766E.toInt(), colorInt("red"))
    }

    /** The core's JSON (snake_case) and the app's model agree. */
    @Test
    fun theCoresRowShapeParses() {
        val json = """{"main":{"title":"T","dtstart":5,"duration":"P1D","all_day":true,"timezone":"UTC","rrule":"FREQ=YEARLY",
            "availability":0,"access_level":0,"reminders":[1440]},"exceptions":[{"dtstart":7,"dtend":8,"all_day":false,
            "timezone":"UTC","status":2,"availability":1,"access_level":0,"original_instance":6,"original_all_day":false,"reminders":[]}]}"""
        val r = CzJson.decodeFromString(EventRows.serializer(), json)
        assertTrue(r.main.allDay)
        assertEquals(listOf(1440L), r.main.reminders)
        assertEquals(6L, r.exceptions.single().originalInstance)
        assertEquals(2, r.exceptions.single().status)
    }
}
