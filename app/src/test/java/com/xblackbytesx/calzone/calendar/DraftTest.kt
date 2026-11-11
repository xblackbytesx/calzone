package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.EventView
import com.xblackbytesx.calzone.model.QuickDraft
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftTest {
    private val swim = EventView(
        collection = "1",
        name = "swim.ics",
        instance = "20261111T160000",
        title = "Swimming lesson",
        location = "Sports hall",
        startDate = "2026-11-11",
        startTime = "16:00",
        endDate = "2026-11-11",
        endTime = "17:00",
        repeat = "weekly",
        recurring = true,
        alarms = listOf(60, 15),
    )

    private fun json(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test
    fun onlyWhatChangedIsSent() {
        val d = draftOf(swim)
        assertEquals(listOf(15L, 60L), d.alarms)
        assertTrue("nothing changed", changeBody(d, d, isNew = false).isEmpty())
        assertEquals(json("""{"summary":"Swim"}"""), changeBody(d.copy(title = " Swim "), d, false))
        val later = d.withStart(d.startDate, LocalTime.of(17, 0))
        assertEquals(json("""{"start":"2026-11-11T17:00","end":"2026-11-11T18:00"}"""), changeBody(later, d, false))
        val reminders = changeBody(d.copy(alarms = listOf(30)), d, false)
        assertEquals(json("""{"alarms":[30]}"""), reminders)
        assertTrue(remindersOnly(reminders))
        assertFalse(remindersOnly(changeBody(d.copy(title = "x", alarms = listOf(30)), d, false)))
    }

    @Test
    fun repeatsAndTheirEnds() {
        val d = draftOf(swim)
        assertEquals(
            json("""{"repeat":{"freq":"weekly","until":"2027-07-07"}}"""),
            changeBody(d.copy(repeatEnd = RepeatEnd.UNTIL, until = LocalDate.parse("2027-07-07")), d, false),
        )
        assertEquals(json("""{"repeat":{"freq":"weekly","count":10}}"""), changeBody(d.copy(repeatEnd = RepeatEnd.COUNT, count = 10), d, false))
        assertEquals(json("""{"repeat":null}"""), changeBody(d.copy(repeat = "none"), d, false))
        // A repeat set elsewhere is kept unless changed.
        val custom = d.copy(repeat = "custom")
        assertTrue(changeBody(custom.copy(count = 3, repeatEnd = RepeatEnd.COUNT), custom, false).isEmpty())
        val back = draftOf(swim.copy(repeatUntil = "2027-07-07"))
        assertEquals(RepeatEnd.UNTIL, back.repeatEnd)
    }

    @Test
    fun allDayEndsAreTheLastDayShownAndTheDayAfterSent() {
        val d = draftOf(swim.copy(allDay = true, startTime = "", endTime = "", endDate = "2026-11-13", repeat = "none", recurring = false))
        assertEquals(LocalDate.parse("2026-11-13"), d.endDate)
        val moved = d.withStart(LocalDate.parse("2026-11-20"), null)
        assertEquals(LocalDate.parse("2026-11-22"), moved.endDate)
        assertEquals(json("""{"start":"2026-11-20","end":"2026-11-23"}"""), changeBody(moved, d, false))
        // Switched to timed: the hour it had, or 9 to 10.
        val timed = changeBody(d.copy(allDay = false), d, false)
        assertEquals(json("""{"start":"2026-11-11T09:00","end":"2026-11-13T10:00"}"""), timed)
    }

    @Test
    fun aNewEventSendsEverything() {
        val n = newDraft("1", LocalDateTime.parse("2026-11-11T17:00"), allDay = false, alarms = listOf(15))
        val body = changeBody(n.copy(title = "Dentist"), n, isNew = true)
        assertEquals(setOf("summary", "location", "description", "start", "end", "repeat", "alarms"), body.keys)
        assertEquals("\"2026-11-11T18:00\"", body["end"].toString())
        assertEquals(LocalDateTime.parse("2026-11-11T17:00"), newStart(LocalDate.parse("2026-11-11"), LocalDate.parse("2026-11-11"), LocalTime.of(16, 20)))
        assertEquals(LocalDateTime.parse("2026-11-12T09:00"), newStart(LocalDate.parse("2026-11-12"), LocalDate.parse("2026-11-11"), LocalTime.of(16, 20)))
    }

    @Test
    fun endsKeepTheLengthAndStayAfterTheStart() {
        val d = draftOf(swim)
        val e = d.withEnd(d.startDate, LocalTime.of(15, 0))
        assertEquals(LocalTime.of(17, 0), e.endTime)
        val night = d.withStart(d.startDate, LocalTime.of(23, 30))
        assertEquals(LocalDate.parse("2026-11-12"), night.endDate)
        assertEquals(LocalTime.of(0, 30), night.endTime)
    }

    @Test
    fun quickAddFillsTheForm() {
        val n = newDraft("1", LocalDateTime.parse("2026-11-11T17:00"), allDay = false, alarms = emptyList())
        val q = n.filled(QuickDraft("Dentist", start = "2026-11-12T15:00:00", end = "2026-11-12T16:00:00"))
        assertEquals("Dentist", q.title)
        assertEquals(LocalDateTime.parse("2026-11-12T15:00"), q.start)
        val days = n.filled(QuickDraft("Holiday", allDay = true, start = "2026-12-21", end = "2026-12-24"))
        assertTrue(days.allDay)
        assertEquals(LocalDate.parse("2026-12-23"), days.endDate)
        assertEquals(n.copy(title = "Call mum"), n.filled(QuickDraft("Call mum")))
    }
}
