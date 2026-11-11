package com.xblackbytesx.calzone.reminders

import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Due
import com.xblackbytesx.calzone.model.Occurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {
    private val now = 1_800_000_000L

    private fun due(fire: Long, at: Long, end: Long) =
        Due(fire, Occurrence(collection = "c", name = "e.ics", start = "", end = "", at = at, endAt = end))

    @Test
    fun theLastMomentRung() {
        assertEquals("a fresh install does not ring the past", now, Timing.last(null, now))
        assertEquals(now - 300, Timing.last(now - 300, now))
        assertEquals("the clock went back", now, Timing.last(now + 3600, now))
    }

    @Test
    fun whatRingsNowAndWhatIsScheduled() {
        assertEquals(now - 299 to now + 1, Timing.window(now - 300, now))
        assertEquals("at most a day back", now - Timing.MISSED to now + 1, Timing.window(now - 10 * 86_400, now))
        // A due moment not rung yet (an alarm late, a reminder set a minute ago) is set at once.
        assertEquals(now - 59, Timing.scheduleFrom(now - 60, now))
        assertEquals(now + 10, Timing.next(listOf(now - 5, now + 10, now + 20), now))
        assertNull(Timing.next(emptyList(), now))
    }

    @Test
    fun missedRemindersRingWhileTheyStillMatter() {
        assertTrue("the event still runs", Timing.worthRinging(due(now - 7200, now - 3600, now + 600), now))
        assertTrue("within the hour of its time", Timing.worthRinging(due(now - 1800, now - 1800, now - 1800), now))
        assertFalse("over and long ago", Timing.worthRinging(due(now - 7200, now - 7000, now - 3700), now))
    }

    @Test
    fun beforeTheFirstUnlockOnlyTimes() {
        val times = listOf(now - 600, now - 60, now + 60)
        assertEquals(listOf(now - 60), Timing.lockedDue(times, now - 300, now))
        assertEquals(listOf(now - 600, now - 60), Timing.lockedDue(times, now - 3600, now))
    }

    @Test
    fun theCoresAnswerParses() {
        // calzone_client::calendar::Due: the item flattened under `occurrence`.
        val json = """[{"fire":1767797100,"occurrence":{"collection":"family","name":"swim.ics","instance":"20260107T160000",
            "title":"Swim","location":null,"start":"2026-01-07T16:00:00","end":"2026-01-07T17:00:00","all_day":false,
            "recurring":true,"at":1767798000,"end_at":1767801600,"calendar":"Family","color":"#112233","owner":"Bram",
            "initials":"BV","own":false,"editable":true,"pending":false}}]"""
        val d = CzJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(Due.serializer()), json).single()
        assertEquals(1767797100L, d.fire)
        assertEquals("20260107T160000", d.occurrence.instance)
        assertEquals(1767801600L, d.occurrence.endAt)
    }
}
