package com.xblackbytesx.calzone.bridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.xblackbytesx.calzone.model.ContactRow
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Entry
import com.xblackbytesx.calzone.model.Postal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactColumnsTest {
    private val ada = ContactRow(
        displayName = "Ada Lovelace",
        given = "Ada",
        middle = "Augusta",
        family = "Lovelace",
        company = "Analytical",
        title = "Countess",
        phones = listOf(
            Entry("+44 7700 900001", Phone.TYPE_MOBILE),
            Entry("+44 20 7946 0003", Phone.TYPE_CUSTOM, "Lab"),
        ),
        emails = listOf(Entry("ada@example.org", Email.TYPE_HOME)),
        addresses = listOf(Postal(StructuredPostal.TYPE_HOME, street = "12 St James Sq", city = "London", postcode = "SW1Y 4JH", country = "UK")),
        birthday = "1815-12-10",
        note = "Poet",
        groups = listOf("Family", "Science"),
    )

    private val ids = mapOf("Family" to 7L, "Science" to 8L)

    /** What the provider hands back: every value as text. */
    private fun asRead(rows: List<DataRow>) = rows.map { d -> d.copy(values = d.values.mapValues { it.value?.toString() }) }

    @Test
    fun aContactReadsBackAsWritten() {
        val rows = dataRows(ada, ids::get)
        val back = contactOf(asRead(rows), { id -> ids.entries.firstOrNull { it.value == id }?.key }, null)
        assertEquals(ada, back)
    }

    @Test
    fun labelsOnlyForCustomTypesAndUnknownGroupsSkipped() {
        val rows = dataRows(ada.copy(phones = listOf(Entry("1", Phone.TYPE_MOBILE, "stale"))), { null })
        val phone = rows.single { it.mime == Phone.CONTENT_ITEM_TYPE }
        assertNull("a label belongs to a custom type only", phone.values[Phone.LABEL])
        assertTrue("no group id, no membership", rows.none { it.mime.endsWith("group_membership") })
    }

    @Test
    fun postalRowsKeepOneStreet() {
        val split = DataRow(
            StructuredPostal.CONTENT_ITEM_TYPE,
            mapOf(StructuredPostal.STREET to "Main st 1", StructuredPostal.POBOX to "PO 9", StructuredPostal.CITY to "Delft", StructuredPostal.TYPE to "2"),
        )
        val formatted = DataRow(StructuredPostal.CONTENT_ITEM_TYPE, mapOf(StructuredPostal.FORMATTED_ADDRESS to "Main st 1, Delft", StructuredPostal.TYPE to "3"))
        val c = contactOf(listOf(split, formatted), { null }, null)
        assertEquals("Main st 1, PO 9", c.addresses[0].street)
        assertEquals(StructuredPostal.TYPE_WORK, c.addresses[0].kind)
        assertEquals("Main st 1, Delft", c.addresses[1].street)
    }

    @Test
    fun theCoreShapeParses() {
        // The JSON calzone_core::android_contacts::row writes.
        val json = """{"uid":"ada","display_name":"Ada Lovelace","prefix":"","given":"Ada","middle":"","family":"Lovelace",
            "suffix":"","company":"","title":"","phones":[{"value":"+31 6 1","kind":0,"label":"Lab"}],"emails":[],
            "addresses":[{"kind":1,"label":null,"street":"Main st 1","city":"Delft","region":"","postcode":"","country":""}],
            "birthday":"--03-15","note":"","groups":["Family"],"photo":"/9j/"}"""
        val r = CzJson.decodeFromString(ContactRow.serializer(), json)
        assertEquals(Entry("+31 6 1", 0, "Lab"), r.phones.single())
        assertEquals("--03-15", r.birthday)
        assertEquals("/9j/", r.photo)
    }

    @Test
    fun photoHashIgnoresFolding() {
        assertEquals(ContactsBridge.hash("/9j/4AAQSkZJRg=="), ContactsBridge.hash("/9j/4AAQ\r\n SkZJRg=="))
        assertTrue(ContactsBridge.hash("a") != ContactsBridge.hash("b"))
    }
}
