package com.xblackbytesx.calzone.home

import com.xblackbytesx.calzone.model.Conflict
import com.xblackbytesx.calzone.model.ConflictDetail
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Lost
import com.xblackbytesx.calzone.model.MergeConflict
import java.io.File
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class ExplainTest {
    @Test
    fun fieldsInPlainWords() {
        val d = ConflictDetail.Merged(
            listOf(
                MergeConflict("VEVENT", "a", null, Lost.Field("SUMMARY", listOf("SUMMARY:Dentist (Mila)"), listOf("SUMMARY;LANGUAGE=nl:Tandarts\\, vroeg"))),
                MergeConflict(
                    "VEVENT",
                    "a",
                    null,
                    Lost.Field(
                        "TIME",
                        listOf("DTSTART;TZID=Europe/Amsterdam:20260310T090000", "DTEND;TZID=Europe/Amsterdam:20260310T100000"),
                        listOf("DTSTART:20260311T090000Z", "DTEND:20260312T100000Z"),
                    ),
                ),
                MergeConflict("VCARD", "c", null, Lost.Field("item1.TEL", listOf("item1.TEL:+31 6 1"), listOf("item1.TEL:+31 6 2"))),
                MergeConflict("VEVENT", "a", null, Lost.Delete),
            ),
        )
        assertEquals(
            listOf(
                Explanation.Field(FieldLabel.TITLE, "SUMMARY", "Dentist (Mila)", "Tandarts, vroeg"),
                Explanation.Field(FieldLabel.TIME, "TIME", "2026-03-10 09:00 – 10:00", "2026-03-11 09:00 – 2026-03-12 10:00"),
                Explanation.Field(FieldLabel.OTHER, "item1.TEL", "+31 6 1", "+31 6 2"),
                Explanation.DeleteUndone,
            ),
            explain(d),
        )
        assertEquals(FieldLabel.PHONE, label("TEL"))
        assertEquals("2026-03-10", prettyTime("20260310"))
        assertEquals("http://x", valueOf("URL:http://x"))
        assertEquals("a:b", valueOf("X-Y;P=\"q:r\":a:b"))
        assertEquals(listOf(Explanation.Refused("read_only")), explain(ConflictDetail.Rejected("read_only", "read-only", null)))
    }

    @Test
    fun titlesFromTheText() {
        assertEquals("Dentist", titleIn("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nSUMMARY:Dentist\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"))
        assertEquals("Mila", titleIn("BEGIN:VCARD\nFN:Mila\nEND:VCARD"))
        assertEquals(null, titleIn(null))
    }

    /** The server's answer golden carries a real merge conflict; the app words it. */
    @Test
    fun theServersConflictParses() {
        val f = System.getProperty("cz.golden")?.let(::File)?.resolve("answer.json")
        assumeTrue("golden files missing", f?.isFile == true)
        val answer = CzJson.parseToJsonElement(f!!.readText()).jsonObject
        val conflicts = answer["pushed"]!!.jsonArray[0].jsonObject["conflicts"]!!
        val c = Conflict(1, "c", "swim.ics", 0, CzJson.decodeFromJsonElement(ConflictDetail.serializer(), kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive("merged"))
            put("conflicts", conflicts)
        }))
        val e = explain(c.detail).single() as Explanation.Field
        assertEquals(FieldLabel.PLACE, e.label)
        assertEquals("Outdoor pool", e.kept)
        assertEquals("Sports hall", e.lost)
    }
}
