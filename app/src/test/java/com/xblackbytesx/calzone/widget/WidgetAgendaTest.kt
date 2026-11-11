package com.xblackbytesx.calzone.widget

import com.xblackbytesx.calzone.model.Occurrence
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetAgendaTest {
    private fun occ(title: String, start: String, end: String, endAt: Long, allDay: Boolean = false) =
        Occurrence(collection = "c", name = "$title.ics", title = title, start = start, end = end, allDay = allDay, endAt = endAt)

    @Test
    fun todaysPastEventsGoAndTwoWeeksShow() {
        val today = LocalDate.parse("2026-11-11")
        val now = 1_000L
        val days = widgetAgenda(
            listOf(
                occ("Over", "2026-11-11T08:00:00", "2026-11-11T09:00:00", endAt = 900),
                occ("Birthday", "2026-11-11", "2026-11-12", endAt = 500, allDay = true),
                occ("Swim", "2026-11-11T16:00:00", "2026-11-11T17:00:00", endAt = 2000),
                occ("Later", "2026-11-24T10:00:00", "2026-11-24T11:00:00", endAt = 9000),
                occ("Too late", "2026-11-25T10:00:00", "2026-11-25T11:00:00", endAt = 9000),
            ),
            today,
            now,
        )
        assertEquals(listOf("2026-11-11", "2026-11-24"), days.map { it.date.toString() })
        assertEquals(listOf("Birthday", "Swim"), days[0].entries.map { it.occurrence.title })
    }
}
