package com.xblackbytesx.calzone.calendar

import com.xblackbytesx.calzone.model.Occurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridTest {
    private fun occ(title: String, start: String, end: String, allDay: Boolean = false) =
        Occurrence(collection = "c", name = "$title.ics", title = title, start = start, end = end, allDay = allDay)

    private fun d(s: String) = LocalDate.parse(s)

    @Test
    fun weeksStartOnThePhonesFirstDay() {
        // 2026-11-11 is a Wednesday.
        assertEquals(d("2026-11-09"), weekStart(d("2026-11-11"), DayOfWeek.MONDAY))
        assertEquals(d("2026-11-08"), weekStart(d("2026-11-11"), DayOfWeek.SUNDAY))
        assertEquals(d("2026-11-09"), weekStart(d("2026-11-09"), DayOfWeek.MONDAY))
        val weeks = monthWeeks(d("2026-11-11"), DayOfWeek.MONDAY)
        assertEquals(d("2026-10-26"), weeks.first().first())
        assertEquals(d("2026-12-06"), weeks.last().last())
        assertEquals(6, weeks.size)
        assertEquals(5, monthWeeks(d("2026-02-01"), DayOfWeek.MONDAY).size)
    }

    @Test
    fun pagesAreStableAndKeepThePlaceInThem() {
        val wed = d("2026-11-11")
        for (view in listOf(CalendarView.DAY, CalendarView.WEEK, CalendarView.MONTH)) {
            for (first in listOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)) {
                val p = Pages.of(view, wed, first)
                assertTrue(p in 0 until Pages.count(view, first))
                assertEquals(wed, Pages.date(view, p, wed, first))
                assertEquals(p + 1, Pages.of(view, Pages.date(view, p + 1, wed, first), first))
            }
        }
        assertEquals(d("2026-11-18"), Pages.date(CalendarView.WEEK, Pages.of(CalendarView.WEEK, wed, DayOfWeek.MONDAY) + 1, wed, DayOfWeek.MONDAY))
        assertEquals(d("2026-12-11"), Pages.date(CalendarView.MONTH, Pages.of(CalendarView.MONTH, wed, DayOfWeek.MONDAY) + 1, wed, DayOfWeek.MONDAY))
        // A week's page is the same whichever day of it is asked.
        assertEquals(Pages.of(CalendarView.WEEK, d("2026-11-09"), DayOfWeek.MONDAY), Pages.of(CalendarView.WEEK, d("2026-11-15"), DayOfWeek.MONDAY))
        assertEquals(Pages.of(CalendarView.WEEK, d("2026-11-15"), DayOfWeek.SUNDAY), Pages.of(CalendarView.WEEK, d("2026-11-21"), DayOfWeek.SUNDAY))
    }

    @Test
    fun overlappingEventsShareTheWidth() {
        val day = d("2026-11-11")
        val pieces = timedPieces(
            listOf(
                occ("Swim", "2026-11-11T16:00:00", "2026-11-11T17:00:00"),
                occ("Call", "2026-11-11T16:30:00", "2026-11-11T17:15:00"),
                occ("Parcel", "2026-11-11T16:45:00", "2026-11-11T17:00:00"),
                occ("Piano", "2026-11-11T18:00:00", "2026-11-11T18:45:00"),
                // Starts when Swim ends: Parcel's column is free again.
                occ("Tea", "2026-11-11T17:00:00", "2026-11-11T17:30:00"),
                occ("Birthday", "2026-11-11", "2026-11-12", allDay = true),
                occ("Elsewhere", "2026-11-12T09:00:00", "2026-11-12T10:00:00"),
            ),
            day,
        ).associateBy { it.occurrence.title }
        assertEquals(setOf("Swim", "Call", "Parcel", "Piano", "Tea"), pieces.keys)
        assertEquals(listOf(0, 1, 2), listOf("Swim", "Call", "Parcel").map { pieces.getValue(it).col })
        assertEquals(listOf(3, 3, 3, 3), listOf("Swim", "Call", "Parcel", "Tea").map { pieces.getValue(it).cols })
        assertEquals(0, pieces.getValue("Tea").col)
        assertEquals(Piece(pieces.getValue("Piano").occurrence, 18 * 60, 18 * 60 + 45, 0, 1), pieces.getValue("Piano"))
    }

    @Test
    fun shortEventsTakeRoomAndNightsSplit() {
        val day = d("2026-11-12")
        val pieces = timedPieces(
            listOf(
                occ("Ping", "2026-11-12T09:00:00", "2026-11-12T09:00:00"),
                occ("Next", "2026-11-12T09:10:00", "2026-11-12T09:40:00"),
                occ("Night", "2026-11-12T22:00:00", "2026-11-13T06:00:00"),
                occ("Late", "2026-11-12T23:00:00", "2026-11-13T00:00:00"),
            ),
            day,
        ).associateBy { it.occurrence.title }
        // A moment takes half an hour of room, so "Next" goes beside it.
        assertEquals(9 * 60, pieces.getValue("Ping").end)
        assertEquals(1, pieces.getValue("Next").col)
        assertEquals(1440, pieces.getValue("Night").end)
        assertEquals(1440, pieces.getValue("Late").end)
        val after = timedPieces(listOf(occ("Night", "2026-11-12T22:00:00", "2026-11-13T06:00:00")), d("2026-11-13")).single()
        assertEquals(0 to 360, after.start to after.end)
    }

    @Test
    fun longEventsGoOnTheAllDayRow() {
        assertTrue(onAllDayRow(occ("Birthday", "2026-11-11", "2026-11-12", allDay = true)))
        assertTrue(onAllDayRow(occ("Weekend", "2026-11-14T10:00:00", "2026-11-16T15:00:00")))
        assertFalse(onAllDayRow(occ("Night", "2026-11-12T22:00:00", "2026-11-13T06:00:00")))
        assertTrue(timedPieces(listOf(occ("Weekend", "2026-11-14T10:00:00", "2026-11-16T15:00:00")), d("2026-11-14")).isEmpty())
    }

    @Test
    fun barsTakeLanesLongestFirst() {
        val week = weekOf(d("2026-11-11"), DayOfWeek.MONDAY)
        val items = listOf(
            occ("Dentist", "2026-11-11T09:00:00", "2026-11-11T09:30:00"),
            occ("Birthday", "2026-11-11", "2026-11-12", allDay = true),
            occ("Weekend", "2026-11-14T10:00:00", "2026-11-16T15:00:00"),
            occ("Trip", "2026-11-05", "2026-11-11", allDay = true),
            occ("Other week", "2026-11-20", "2026-11-21", allDay = true),
        )
        val allDay = spans(items, week, timed = false).associateBy { it.occurrence.title }
        assertEquals(setOf("Birthday", "Weekend", "Trip"), allDay.keys)
        // The trip began the week before and ends on Tuesday (the end is the day after).
        assertEquals(0 to 1, allDay.getValue("Trip").let { it.first to it.last })
        // The weekend runs into next week: it stops at Sunday.
        assertEquals(5 to 6, allDay.getValue("Weekend").let { it.first to it.last })
        assertEquals(0, allDay.getValue("Birthday").lane)

        val month = spans(items + occ("Lunch", "2026-11-11T12:00:00", "2026-11-11T13:00:00"), week, timed = true).associateBy { it.occurrence.title }
        assertEquals(listOf(0, 1, 2), listOf("Birthday", "Dentist", "Lunch").map { month.getValue(it).lane })
        assertFalse(month.getValue("Dentist").bar)
    }

    @Test
    fun theHoursOpenNearNowOnToday() {
        assertEquals(7 * 60, openingMinute(false, LocalTime.of(15, 0)))
        assertEquals(13 * 60 + 30, openingMinute(true, LocalTime.of(15, 0)))
        assertEquals(0, openingMinute(true, LocalTime.of(0, 30)))
        assertEquals(16 * 60, openingMinute(true, LocalTime.of(23, 30)))
    }

    @Test
    fun theSmallMonthsDotsAndList() {
        fun c(title: String, color: String, start: String, end: String, allDay: Boolean = false) =
            Occurrence(collection = color, name = "$title.ics", title = title, start = start, end = end, allDay = allDay, color = color)
        val items = listOf(
            c("Swim", "#3a7bd5", "2026-11-11T16:00:00", "2026-11-11T17:00:00"),
            c("Piano", "#3a7bd5", "2026-11-11T18:00:00", "2026-11-11T18:45:00"),
            c("Book club", "#d53a7b", "2026-11-11T20:00:00", "2026-11-11T22:00:00"),
            c("Trip", "#00897b", "2026-11-19", "2026-11-21", allDay = true),
            c("December", "#00897b", "2026-12-02T09:00:00", "2026-12-02T10:00:00"),
        )
        val dots = dayColors(items, monthWeeks(d("2026-11-11"), DayOfWeek.MONDAY).flatten())
        assertEquals("each calendar once, all-day first", listOf("#3a7bd5", "#d53a7b"), dots[d("2026-11-11")])
        assertEquals(listOf("#00897b"), dots[d("2026-11-20")])
        assertEquals("the next month's days in the grid have theirs", listOf("#00897b"), dots[d("2026-12-02")])
        assertEquals(null, dots[d("2026-11-12")])
        val list = monthDays(items, d("2026-11-25"), today = d("2026-11-15"))
        assertEquals(listOf("2026-11-11", "2026-11-15", "2026-11-19", "2026-11-20"), list.map { it.date.toString() })
    }
}
