package com.xblackbytesx.calzone.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Mirrors of the core's and the client's JSON (calzone-server `calzone_core::sync`
 * and `calzone_client::store`): the shapes `/sync/v1` sends and the FFI
 * returns. The server's golden files must parse with these (CoreContractTest).
 */
val CzJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class Account(
    val name: String,
    @SerialName("display_name") val displayName: String,
    val device: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("push_hosts") val pushHosts: List<String> = emptyList(),
    val push: Boolean = false,
)

@Serializable
data class SignedIn(val token: String, val account: Account)

@Serializable
data class Mine(
    val color: String? = null,
    val name: String? = null,
    val hidden: Boolean = false,
    val order: Int? = null,
    @SerialName("default_alarm") val defaultAlarm: Int? = null,
)

@Serializable
data class Collection(
    val id: String,
    /** `calendar` or `addressbook`. */
    val kind: String,
    val owner: String,
    @SerialName("owner_name") val ownerName: String,
    val name: String,
    val description: String = "",
    val color: String? = null,
    val components: List<String> = emptyList(),
    /** `read`, `write`, `admin` or `owner`. */
    val access: String,
    @SerialName("read_only") val readOnly: Boolean,
    val subscribed: Boolean,
    val state: Long,
    val mine: Mine = Mine(),
) {
    val isCalendar: Boolean get() = kind == "calendar"
    val isMine: Boolean get() = access == "owner"

    /** The person's own name for it, else the shared one. */
    val label: String get() = mine.name?.takeIf { it.isNotBlank() } ?: name

    /** The person's own color, else the shared one. */
    val shownColor: String? get() = mine.color ?: color
}

@Serializable
data class Collections(val collections: List<Collection>)

@Serializable
data class StoredCollection(val info: Collection, val state: Long? = null, val pending: Int = 0)

@Serializable
data class ObjectMeta(
    val collection: String,
    val name: String,
    val revision: Long,
    val kind: String,
    val uid: String,
    val summary: String? = null,
    val starts: Long? = null,
    val ends: Long? = null,
    val recurring: Boolean = false,
    val undated: Boolean = false,
    val pending: Boolean = false,
)

/** What one engine run did. */
@Serializable
data class Report(
    val collections: Int = 0,
    @SerialName("collections_gone") val collectionsGone: Int = 0,
    val pushed: Int = 0,
    val rejected: Int = 0,
    val pulled: Int = 0,
    val conflicts: Int = 0,
)

/** One thing the phone's edit lost in a merge (calzone_core::merge::Lost). */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface Lost {
    /** Both sides changed `field`: the server's lines were kept. */
    @Serializable
    @SerialName("field")
    data class Field(val field: String, val kept: List<String>, val lost: List<String>) : Lost

    /** The phone deleted it while the server changed it: the server's copy stayed. */
    @Serializable
    @SerialName("delete")
    data object Delete : Lost

    /** The server deleted it while the phone changed it. */
    @Serializable
    @SerialName("edit")
    data class Edit(val lost: String) : Lost
}

@Serializable
data class MergeConflict(
    val component: String,
    val uid: String? = null,
    @SerialName("recurrence_id") val recurrenceId: String? = null,
    val lost: Lost,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
sealed interface ConflictDetail {
    @Serializable
    @SerialName("merged")
    data class Merged(val conflicts: List<MergeConflict>) : ConflictDetail

    /** `reason`: `read_only`, `invalid`, `uid_taken` or `bad_name`. */
    @Serializable
    @SerialName("rejected")
    data class Rejected(val reason: String, val message: String, val body: String? = null) : ConflictDetail

    @Serializable
    @SerialName("collection_gone")
    data class CollectionGone(val body: String? = null) : ConflictDetail
}

@Serializable
data class Conflict(val id: Long, val collection: String, val name: String, val at: Long, val detail: ConflictDetail)

/** The decrypted push message: which collections changed. */
@Serializable
data class Changed(val id: String, val state: Long)

@Serializable
data class Notice(val collections: List<Changed> = emptyList(), val all: Boolean = false)

/**
 * One `CalendarContract.Events` row (calzone_core::android::EventRow): what
 * the bridge writes for a stored event, and what it reads back after
 * another app edited it. Times are UTC milliseconds.
 */
@Serializable
data class EventRow(
    val title: String? = null,
    val location: String? = null,
    val description: String? = null,
    val dtstart: Long,
    val dtend: Long? = null,
    val duration: String? = null,
    @SerialName("all_day") val allDay: Boolean = false,
    val timezone: String = "UTC",
    @SerialName("end_timezone") val endTimezone: String? = null,
    val rrule: String? = null,
    val rdate: String? = null,
    val exrule: String? = null,
    val exdate: String? = null,
    val status: Int? = null,
    val availability: Int = 0,
    @SerialName("access_level") val accessLevel: Int = 0,
    val uid: String? = null,
    @SerialName("original_instance") val originalInstance: Long? = null,
    @SerialName("original_all_day") val originalAllDay: Boolean? = null,
    val reminders: List<Long> = emptyList(),
)

@Serializable
data class EventRows(val main: EventRow, val exceptions: List<EventRow> = emptyList())

/** A phone or email data row (calzone_core::android_contacts::Entry); `kind` is Android's type number. */
@Serializable
data class Entry(val value: String, val kind: Int, val label: String? = null)

/** A `StructuredPostal` data row. */
@Serializable
data class Postal(
    val kind: Int,
    val label: String? = null,
    val street: String = "",
    val city: String = "",
    val region: String = "",
    val postcode: String = "",
    val country: String = "",
)

/**
 * One raw contact with its data rows (calzone_core::android_contacts::ContactRow):
 * what the contacts bridge writes for a stored vCard, and what it reads back
 * after another app edited it.
 */
@Serializable
data class ContactRow(
    val uid: String? = null,
    @SerialName("display_name") val displayName: String = "",
    val prefix: String = "",
    val given: String = "",
    val middle: String = "",
    val family: String = "",
    val suffix: String = "",
    val company: String = "",
    val title: String = "",
    val phones: List<Entry> = emptyList(),
    val emails: List<Entry> = emptyList(),
    val addresses: List<Postal> = emptyList(),
    /** `yyyy-MM-dd`, or `--MM-dd` without a year. */
    val birthday: String? = null,
    val note: String = "",
    /** Group titles. */
    val groups: List<String> = emptyList(),
    /** The picture, base64. */
    val photo: String? = null,
)

/** A change from the server the bridge must mirror; `name` null is the collection itself. */
@Serializable
data class LocalChange(val seq: Long, val collection: String, val name: String? = null, val deleted: Boolean = false)

/** The server's error body. */
@Serializable
data class ServerError(val error: String = "", val code: String = "")
