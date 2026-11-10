package com.xblackbytesx.calzone.bridge

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.xblackbytesx.calzone.model.ContactRow
import com.xblackbytesx.calzone.model.Entry
import com.xblackbytesx.calzone.model.Postal

/** One `Data` row: its mimetype and its columns (`data1` to `data14`, as text when read). */
data class DataRow(val mime: String, val values: Map<String, Any?>)

/** The data kinds the bridge owns; anything else on a raw contact is left alone. */
val OWNED_MIMES = listOf(
    StructuredName.CONTENT_ITEM_TYPE,
    Organization.CONTENT_ITEM_TYPE,
    Phone.CONTENT_ITEM_TYPE,
    Email.CONTENT_ITEM_TYPE,
    StructuredPostal.CONTENT_ITEM_TYPE,
    Event.CONTENT_ITEM_TYPE,
    Note.CONTENT_ITEM_TYPE,
    GroupMembership.CONTENT_ITEM_TYPE,
)

private fun String.orNull(): String? = trim().ifEmpty { null }

/**
 * The data rows for a raw contact (the column names are compile-time
 * constants, so this is pure and tested on the JVM). The photo is written
 * apart (decision 45). `group` gives the row id of a group title, or null
 * when there is none.
 */
fun dataRows(r: ContactRow, group: (String) -> Long?): List<DataRow> {
    val out = mutableListOf<DataRow>()
    if (listOf(r.displayName, r.prefix, r.given, r.middle, r.family, r.suffix).any { it.isNotBlank() }) {
        out += DataRow(
            StructuredName.CONTENT_ITEM_TYPE,
            mapOf(
                StructuredName.DISPLAY_NAME to r.displayName.orNull(),
                StructuredName.PREFIX to r.prefix.orNull(),
                StructuredName.GIVEN_NAME to r.given.orNull(),
                StructuredName.MIDDLE_NAME to r.middle.orNull(),
                StructuredName.FAMILY_NAME to r.family.orNull(),
                StructuredName.SUFFIX to r.suffix.orNull(),
            ),
        )
    }
    if (r.company.isNotBlank() || r.title.isNotBlank()) {
        out += DataRow(
            Organization.CONTENT_ITEM_TYPE,
            mapOf(Organization.COMPANY to r.company.orNull(), Organization.TITLE to r.title.orNull(), Organization.TYPE to Organization.TYPE_WORK),
        )
    }
    for (p in r.phones) {
        out += DataRow(Phone.CONTENT_ITEM_TYPE, mapOf(Phone.NUMBER to p.value, Phone.TYPE to p.kind, Phone.LABEL to label(p.kind, p.label)))
    }
    for (e in r.emails) {
        out += DataRow(Email.CONTENT_ITEM_TYPE, mapOf(Email.ADDRESS to e.value, Email.TYPE to e.kind, Email.LABEL to label(e.kind, e.label)))
    }
    for (a in r.addresses) {
        out += DataRow(
            StructuredPostal.CONTENT_ITEM_TYPE,
            mapOf(
                StructuredPostal.STREET to a.street.orNull(),
                StructuredPostal.CITY to a.city.orNull(),
                StructuredPostal.REGION to a.region.orNull(),
                StructuredPostal.POSTCODE to a.postcode.orNull(),
                StructuredPostal.COUNTRY to a.country.orNull(),
                StructuredPostal.TYPE to a.kind,
                StructuredPostal.LABEL to label(a.kind, a.label),
            ),
        )
    }
    r.birthday?.orNull()?.let {
        out += DataRow(Event.CONTENT_ITEM_TYPE, mapOf(Event.START_DATE to it, Event.TYPE to Event.TYPE_BIRTHDAY))
    }
    r.note.orNull()?.let { out += DataRow(Note.CONTENT_ITEM_TYPE, mapOf(Note.NOTE to it)) }
    for (title in r.groups.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
        group(title)?.let { out += DataRow(GroupMembership.CONTENT_ITEM_TYPE, mapOf(GroupMembership.GROUP_ROW_ID to it)) }
    }
    return out
}

/** A label belongs to a custom type only. */
private fun label(kind: Int, label: String?): String? = if (kind == CUSTOM) label?.orNull() else null

private const val CUSTOM = 0

private fun DataRow.text(column: String): String = values[column]?.toString()?.trim().orEmpty()

private fun DataRow.int(column: String, default: Int): Int = values[column]?.toString()?.trim()?.toIntOrNull() ?: default

/**
 * A raw contact's data rows (as read back after another app edited them)
 * as the core reads it. `groupTitle` gives a group's title by row id (null
 * for a deleted group); `photo` is decided by the caller.
 */
fun contactOf(rows: List<DataRow>, groupTitle: (Long) -> String?, photo: String?): ContactRow {
    fun first(mime: String) = rows.firstOrNull { it.mime == mime }
    fun all(mime: String) = rows.filter { it.mime == mime }
    val name = first(StructuredName.CONTENT_ITEM_TYPE)
    val org = first(Organization.CONTENT_ITEM_TYPE)
    return ContactRow(
        displayName = name?.text(StructuredName.DISPLAY_NAME).orEmpty(),
        prefix = name?.text(StructuredName.PREFIX).orEmpty(),
        given = name?.text(StructuredName.GIVEN_NAME).orEmpty(),
        middle = name?.text(StructuredName.MIDDLE_NAME).orEmpty(),
        family = name?.text(StructuredName.FAMILY_NAME).orEmpty(),
        suffix = name?.text(StructuredName.SUFFIX).orEmpty(),
        company = org?.text(Organization.COMPANY).orEmpty(),
        title = org?.text(Organization.TITLE).orEmpty(),
        phones = all(Phone.CONTENT_ITEM_TYPE).map {
            Entry(it.text(Phone.NUMBER), it.int(Phone.TYPE, Phone.TYPE_OTHER), it.text(Phone.LABEL).orNull())
        }.filter { it.value.isNotEmpty() },
        emails = all(Email.CONTENT_ITEM_TYPE).map {
            Entry(it.text(Email.ADDRESS), it.int(Email.TYPE, Email.TYPE_OTHER), it.text(Email.LABEL).orNull())
        }.filter { it.value.isNotEmpty() },
        addresses = all(StructuredPostal.CONTENT_ITEM_TYPE).map { postal(it) }
            .filter { listOf(it.street, it.city, it.region, it.postcode, it.country).any(String::isNotEmpty) },
        birthday = all(Event.CONTENT_ITEM_TYPE)
            .firstOrNull { it.int(Event.TYPE, -1) == Event.TYPE_BIRTHDAY }
            ?.text(Event.START_DATE)?.orNull(),
        note = first(Note.CONTENT_ITEM_TYPE)?.text(Note.NOTE).orEmpty(),
        groups = all(GroupMembership.CONTENT_ITEM_TYPE)
            .mapNotNull { it.text(GroupMembership.GROUP_ROW_ID).toLongOrNull()?.let(groupTitle) }
            .distinct(),
        photo = photo,
    )
}

/**
 * A postal row. CalZone keeps one street line: a PO box or neighborhood
 * typed apart joins it, and an address another app wrote only as one
 * formatted text becomes the street.
 */
private fun postal(d: DataRow): Postal {
    val street = listOf(StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD)
        .map { d.text(it) }.filter { it.isNotEmpty() }.joinToString(", ")
    val p = Postal(
        kind = d.int(StructuredPostal.TYPE, StructuredPostal.TYPE_OTHER),
        label = d.text(StructuredPostal.LABEL).orNull(),
        street = street,
        city = d.text(StructuredPostal.CITY),
        region = d.text(StructuredPostal.REGION),
        postcode = d.text(StructuredPostal.POSTCODE),
        country = d.text(StructuredPostal.COUNTRY),
    )
    val empty = listOf(p.street, p.city, p.region, p.postcode, p.country).all(String::isEmpty)
    return if (empty) p.copy(street = d.text(StructuredPostal.FORMATTED_ADDRESS)) else p
}
