package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.Occurrence
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgendaTest {
    private fun occ(title: String, start: String, end: String, allDay: Boolean = false) =
        Occurrence(collection = "c", name = "$title.ics", title = title, start = start, end = end, allDay = allDay)

    private val today = LocalDate.parse("2026-01-07")
    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun daysHoldTheirEventsAllDayFirstThenByTime() {
        val days = agendaDays(
            listOf(
                occ("Swim", "2026-01-07T16:00:00", "2026-01-07T17:00:00"),
                occ("Dentist", "2026-01-07T09:00:00", "2026-01-07T09:30:00"),
                occ("Birthday", "2026-01-07", "2026-01-08", allDay = true),
                occ("Later", "2026-01-12T10:00:00", "2026-01-12T11:00:00"),
            ),
            d("2026-01-01"), d("2026-02-01"), today,
        )
        assertEquals(listOf(d("2026-01-07"), d("2026-01-12")), days.map { it.date })
        assertEquals(listOf("Birthday", "Dentist", "Swim"), days[0].entries.map { it.occurrence.title })
        assertEquals(DayTime.Range(LocalTime.of(9, 0), LocalTime.of(9, 30)), days[0].entries[1].time)
    }

    @Test
    fun todayIsAlwaysThereAndRangesAreKept() {
        val days = agendaDays(listOf(occ("Old", "2025-12-01T10:00:00", "2025-12-01T11:00:00")), d("2026-01-01"), d("2026-02-01"), today)
        assertEquals(1, days.size)
        assertEquals(today, days[0].date)
        assertTrue(days[0].entries.isEmpty())
        assertTrue("today outside the range is left out", agendaDays(emptyList(), d("2026-03-01"), d("2026-04-01"), today).isEmpty())
    }

    @Test
    fun longerEventsShowOnEveryDay() {
        val holiday = agendaDays(listOf(occ("Holiday", "2026-01-07", "2026-01-10", allDay = true)), d("2026-01-01"), d("2026-02-01"), today)
        assertEquals(listOf(d("2026-01-07"), d("2026-01-08"), d("2026-01-09")), holiday.map { it.date })

        val trip = agendaDays(listOf(occ("Trip", "2026-01-07T20:00:00", "2026-01-09T10:00:00")), d("2026-01-01"), d("2026-02-01"), today)
        assertEquals(
            listOf(DayTime.Starts(LocalTime.of(20, 0)), DayTime.AllDay, DayTime.Ends(LocalTime.of(10, 0))),
            trip.map { it.entries.single().time },
        )
        // Ending at midnight is the day before's.
        val late = agendaDays(listOf(occ("Party", "2026-01-07T20:00:00", "2026-01-08T00:00:00")), d("2026-01-01"), d("2026-02-01"), today)
        assertEquals(listOf(d("2026-01-07")), late.map { it.date })
        assertEquals(DayTime.Range(LocalTime.of(20, 0), LocalTime.MIDNIGHT), late[0].entries.single().time)
        // No length: one moment.
        val moment = agendaDays(listOf(occ("Call", "2026-01-07T12:00:00", "2026-01-07T12:00:00")), d("2026-01-01"), d("2026-02-01"), today)
        assertEquals(DayTime.Range(LocalTime.NOON, null), moment[0].entries.single().time)
    }
}
