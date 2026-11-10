package com.xblackbytesx.calzone.bridge

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Settings
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.core.Core
import com.xblackbytesx.calzone.core.CoreError
import com.xblackbytesx.calzone.model.Collection
import com.xblackbytesx.calzone.model.ContactRow
import com.xblackbytesx.calzone.prefs.Prefs
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.flow.first
import android.accounts.Account as AndroidAccount

private val Context.contactsBridgeStore by preferencesDataStore("contacts_bridge")

/**
 * The contacts bridge (decision 50 in calzone-server; decisions 17 to 21
 * here): the address books this phone shows mirrored into Android's
 * contacts under the CalZone account, so the Contacts app, the dialer and
 * messengers show them, and edits made there come back as pending edits.
 *
 * - A raw contact is keyed by `SOURCE_ID` (the vCard's name) and `SYNC1`
 *   (its address book). `SYNC2` is a hash of the photo the bridge wrote and
 *   `SYNC3` the file id Android gave it, so a photo Android re-encoded is
 *   not taken for a new one.
 * - [outbound] before a sync: every dirty or deleted raw contact becomes an
 *   edit through the core, compared by meaning with what was written.
 *   Groups another app renamed or deleted first mark their members dirty.
 * - [begin] after a sync: whatever must be written in full, then (through
 *   the [Pass]) every vCard the server changed. A raw contact still dirty
 *   is never overwritten. Data rows are replaced in batches; photos go in
 *   as the display-photo file afterwards (DAVx5's way, decision 45).
 *
 * Writes go through the sync-adapter URIs, by row id.
 */
class ContactsBridge(private val context: Context, private val core: Core) : Mirror {
    private val cr: ContentResolver = context.contentResolver
    private val versionKey = intPreferencesKey("version")
    private val mirroredKey = stringSetPreferencesKey("mirrored")

    /** Raw contacts an edit was refused for (a view-only book): written again from the store. */
    private val rewrite = mutableSetOf<Pair<String, String>>()

    override fun permitted(): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun Uri.asAdapter(a: AndroidAccount): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, a.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, a.type)
        .build()

    private fun raw(id: Long): Uri = android.content.ContentUris.withAppendedId(RawContacts.CONTENT_URI, id)

    private val ofAccount = "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?"

    private fun books(prefs: Prefs): List<Collection> =
        core.collections().map { it.info }.filter { it.kind == "addressbook" && prefs.shows(it.id, it.mine.hidden) }

    /** Where a contact made in another app goes: the person's own first writable book, else any writable one. */
    private fun target(books: List<Collection>): Collection? =
        books.firstOrNull { it.isMine && !it.readOnly } ?: books.firstOrNull { !it.readOnly }

    // --- raw contacts ---

    private class Raw(
        val id: Long,
        val name: String?,
        val book: String?,
        val photoHash: String?,
        val photoFile: String?,
        val dirty: Boolean,
        val deleted: Boolean,
    )

    private fun raws(a: AndroidAccount, extra: String? = null, args: Array<String> = emptyArray()): List<Raw> {
        val out = mutableListOf<Raw>()
        cr.query(
            RawContacts.CONTENT_URI.asAdapter(a),
            arrayOf(RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3, RawContacts.DIRTY, RawContacts.DELETED),
            if (extra == null) ofAccount else "$ofAccount AND ($extra)",
            arrayOf(a.name, a.type) + args,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                out += Raw(
                    id = c.getLong(0),
                    name = c.str(1),
                    book = c.str(2),
                    photoHash = c.str(3),
                    photoFile = c.str(4),
                    dirty = c.getLong(5) == 1L,
                    deleted = c.getLong(6) == 1L,
                )
            }
        }
        return out
    }

    /** Our raw contacts by address book and name. */
    private fun index(a: AndroidAccount): MutableMap<Pair<String, String>, Raw> {
        val out = mutableMapOf<Pair<String, String>, Raw>()
        for (r in raws(a)) if (r.book != null && r.name != null) out[r.book to r.name] = r
        return out
    }

    private fun data(id: Long): List<DataRow> {
        val out = mutableListOf<DataRow>()
        val columns = (1..14).map { "data$it" }
        cr.query(Data.CONTENT_URI, arrayOf(Data.MIMETYPE) + columns, "${Data.RAW_CONTACT_ID} = ?", arrayOf(id.toString()), null)?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(0) ?: continue
                if (mime !in OWNED_MIMES) continue
                out += DataRow(mime, columns.mapIndexed { i, col -> col to c.str(i + 1) }.toMap())
            }
        }
        return out
    }

    /** The photo row's file id, or null when the raw contact has no photo. */
    private fun photoFile(id: Long): String? =
        cr.query(
            Data.CONTENT_URI,
            arrayOf(Photo.PHOTO_FILE_ID, Photo.PHOTO),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> if (c.moveToFirst() && !(c.isNull(0) && c.isNull(1))) c.str(0) ?: THUMBNAIL_ONLY else null }

    /** The picture Android holds for a raw contact: the display photo, else the thumbnail. */
    private fun readPhoto(id: Long): String? {
        val bytes = runCatching {
            cr.openAssetFileDescriptor(Uri.withAppendedPath(raw(id), RawContacts.DisplayPhoto.CONTENT_DIRECTORY), "r")
                ?.use { it.createInputStream().readBytes() }
        }.getOrNull() ?: cr.query(
            Data.CONTENT_URI,
            arrayOf(Photo.PHOTO),
            "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(id.toString(), Photo.CONTENT_ITEM_TYPE),
            null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null }
        return bytes?.takeIf { it.isNotEmpty() }?.let { Base64.getEncoder().encodeToString(it) }
    }

    // --- groups ---

    /** Our groups by title (not deleted); a missing one is made on first use. */
    private inner class GroupIds(private val a: AndroidAccount) {
        private val byTitle = mutableMapOf<String, Long>()

        init {
            cr.query(
                Groups.CONTENT_URI.asAdapter(a),
                arrayOf(Groups._ID, Groups.TITLE),
                "${Groups.ACCOUNT_NAME} = ? AND ${Groups.ACCOUNT_TYPE} = ? AND ${Groups.DELETED} = 0",
                arrayOf(a.name, a.type),
                null,
            )?.use { c -> while (c.moveToNext()) c.str(1)?.let { byTitle.putIfAbsent(it, c.getLong(0)) } }
        }

        val titles: Map<Long, String> get() = byTitle.entries.associate { (t, id) -> id to t }

        fun id(title: String): Long? = byTitle[title] ?: cr.insert(
            Groups.CONTENT_URI.asAdapter(a),
            ContentValues().apply {
                put(Groups.ACCOUNT_NAME, a.name)
                put(Groups.ACCOUNT_TYPE, a.type)
                put(Groups.TITLE, title)
                put(Groups.SOURCE_ID, title)
                put(Groups.SYNC1, MADE_BY_US)
                put(Groups.GROUP_VISIBLE, 1)
                put(Groups.SHOULD_SYNC, 1)
            },
        )?.lastPathSegment?.toLongOrNull()?.also { byTitle[title] = it }

        /** Groups the bridge made that no contact is in any more. */
        fun prune() {
            val ours = mutableListOf<Long>()
            cr.query(
                Groups.CONTENT_URI.asAdapter(a),
                arrayOf(Groups._ID),
                "${Groups.ACCOUNT_NAME} = ? AND ${Groups.ACCOUNT_TYPE} = ? AND ${Groups.SYNC1} = ? AND ${Groups.DIRTY} = 0",
                arrayOf(a.name, a.type, MADE_BY_US),
                null,
            )?.use { c -> while (c.moveToNext()) ours += c.getLong(0) }
            for (id in ours) {
                if (members(id).isEmpty()) {
                    cr.delete(android.content.ContentUris.withAppendedId(Groups.CONTENT_URI, id).asAdapter(a), null, null)
                    byTitle.values.remove(id)
                }
            }
        }
    }

    private fun members(group: Long): Set<Long> {
        val out = mutableSetOf<Long>()
        cr.query(
            Data.CONTENT_URI,
            arrayOf(Data.RAW_CONTACT_ID),
            "${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE, group.toString()),
            null,
        )?.use { c -> while (c.moveToNext()) out += c.getLong(0) }
        return out
    }

    private class TouchedGroup(val id: Long, val sourceId: String?, val title: String?, val deleted: Boolean)

    /**
     * Groups another app renamed or deleted: their members (by membership,
     * and by the vCards that name the old title, since a deleted group's
     * memberships are gone) are marked dirty, so the change reaches every
     * vCard. Returned to be settled after the contacts were read.
     */
    private fun touchGroups(a: AndroidAccount): List<TouchedGroup> {
        val touched = mutableListOf<TouchedGroup>()
        cr.query(
            Groups.CONTENT_URI.asAdapter(a),
            arrayOf(Groups._ID, Groups.SOURCE_ID, Groups.TITLE, Groups.DELETED),
            "${Groups.ACCOUNT_NAME} = ? AND ${Groups.ACCOUNT_TYPE} = ? AND (${Groups.DIRTY} = 1 OR ${Groups.DELETED} = 1)",
            arrayOf(a.name, a.type),
            null,
        )?.use { c -> while (c.moveToNext()) touched += TouchedGroup(c.getLong(0), c.str(1), c.str(2), c.getLong(3) == 1L) }
        if (touched.isEmpty()) return touched
        val dirty = mutableSetOf<Long>()
        for (g in touched) dirty += members(g.id)
        val old = touched.filter { it.deleted || it.title != it.sourceId }.mapNotNull { it.sourceId }.toSet()
        if (old.isNotEmpty()) {
            for (r in raws(a)) {
                val book = r.book ?: continue
                val name = r.name ?: continue
                if (r.id in dirty) continue
                val groups = runCatching { core.contactRow(book, name)?.groups }.getOrNull().orEmpty()
                if (groups.any { it in old }) dirty += r.id
            }
        }
        for (id in dirty) cr.update(raw(id).asAdapter(a), ContentValues().apply { put(RawContacts.DIRTY, 1) }, null, null)
        return touched
    }

    private fun settleGroups(a: AndroidAccount, touched: List<TouchedGroup>) {
        for (g in touched) {
            val uri = android.content.ContentUris.withAppendedId(Groups.CONTENT_URI, g.id).asAdapter(a)
            if (g.deleted) {
                cr.delete(uri, null, null)
            } else {
                cr.update(uri, ContentValues().apply {
                    put(Groups.SOURCE_ID, g.title)
                    put(Groups.DIRTY, 0)
                }, null, null)
            }
        }
    }

    // --- outbound: Android's edits into the store ---

    /** Read every dirty or deleted raw contact into pending edits. */
    override fun outbound(a: AndroidAccount, prefs: Prefs) {
        val touched = touchGroups(a)
        val titles = GroupIds(a).titles
        val all = core.collections().map { it.info }.filter { it.kind == "addressbook" }.associateBy { it.id }
        val target = target(books(prefs))
        for (r in raws(a, "${RawContacts.DIRTY} = 1 OR ${RawContacts.DELETED} = 1")) {
            try {
                one(a, r, all, target, titles)
            } catch (e: CoreError) {
                // Left dirty: the next run tries again. Logged without content.
                Log.w(TAG, "an edit from Android was not taken: ${e.javaClass.simpleName}")
            }
        }
        settleGroups(a, touched)
    }

    private fun one(a: AndroidAccount, r: Raw, books: Map<String, Collection>, target: Collection?, titles: Map<Long, String>) {
        val uri = raw(r.id).asAdapter(a)
        val book = r.book?.let { books[it] }
        when {
            r.deleted -> {
                if (r.name != null && book != null) {
                    // In a view-only book the contact comes back instead.
                    if (book.readOnly) rewrite += book.id to r.name else core.delete(book.id, r.name)
                }
                cr.delete(uri, null, null)
            }
            r.name == null -> {
                // Made in another app. With no book to take it, it waits.
                val to = target ?: return
                val file = photoFile(r.id)
                val row = contactOf(data(r.id), titles::get, if (file != null) readPhoto(r.id) else null)
                val name = core.createFromContactRow(to.id, row, UUID.randomUUID().toString(), context.getString(R.string.no_name))
                cr.update(uri, ContentValues().apply {
                    put(RawContacts.SOURCE_ID, name)
                    put(RawContacts.SYNC1, to.id)
                    put(RawContacts.SYNC2, row.photo?.let(::hash))
                    put(RawContacts.SYNC3, file)
                    put(RawContacts.DIRTY, 0)
                }, null, null)
            }
            book == null -> {
                // Its book is gone from the server; the next pass removes it.
                cr.update(uri, ContentValues().apply { put(RawContacts.DIRTY, 0) }, null, null)
            }
            book.readOnly -> {
                // The person may only read this book: Android's edit is undone.
                rewrite += book.id to r.name
                cr.update(uri, ContentValues().apply { put(RawContacts.DIRTY, 0) }, null, null)
            }
            else -> {
                val stored = core.contactRow(book.id, r.name)
                if (stored == null) {
                    // Deleted on the server meanwhile: the next pass removes it.
                    cr.update(uri, ContentValues().apply { put(RawContacts.DIRTY, 0) }, null, null)
                    return
                }
                val file = photoFile(r.id)
                val photo = when {
                    // The photo the bridge wrote, unchanged; also one Android is still
                    // processing, or could not read at all.
                    r.photoHash != null && (r.photoFile == PENDING || r.photoFile == file) -> stored.photo
                    file == null -> null
                    else -> readPhoto(r.id)
                }
                val row = contactOf(data(r.id), titles::get, photo)
                core.applyContactRow(book.id, r.name, row)
                cr.update(uri, ContentValues().apply {
                    put(RawContacts.SYNC2, photo?.let(::hash))
                    put(RawContacts.SYNC3, if (photo != null) file ?: PENDING else null)
                    put(RawContacts.DIRTY, 0)
                }, null, null)
            }
        }
    }

    // --- inbound: the store into Android ---

    /**
     * Address books this phone shows: one no longer shown leaves Android;
     * one new to it (or every one, after a mapping change or a time without
     * access) is written in full; then every change from the server.
     */
    override suspend fun begin(a: AndroidAccount, prefs: Prefs): Pass {
        ensureSettings(a)
        val books = books(prefs).associateBy { it.id }
        val state = context.contactsBridgeStore.data.first()
        val mirrored = if (state[versionKey] == VERSION) state[mirroredKey].orEmpty() else emptySet()
        val known = index(a)
        val writer = Writer(a, known, books)
        for ((key, r) in known.toMap()) {
            if (key.first !in books) {
                writer.remove(r.id)
                known.remove(key)
            }
        }
        val full = books.keys - mirrored
        for (book in full) {
            val names = core.objects(book).map { it.name }.toSet()
            for (name in names) writer.write(book, name)
            for ((key, r) in known.toMap()) if (key.first == book && key.second !in names && !r.dirty) writer.remove(r.id)
        }
        for ((book, name) in rewrite.toList()) if (book in books && book !in full) writer.write(book, name, force = true)
        rewrite.clear()
        return object : Pass {
            override fun write(collection: String, name: String) {
                if (collection in books && collection !in full) writer.write(collection, name)
            }

            override suspend fun finish() {
                writer.flush()
                writer.photos()
                writer.groups.prune()
                context.contactsBridgeStore.edit {
                    it[versionKey] = VERSION
                    it[mirroredKey] = books.keys
                }
            }
        }
    }

    override suspend fun forget() {
        context.contactsBridgeStore.edit { it.remove(versionKey) }
    }

    /** Our account's contacts are shown, also those in no group. */
    private fun ensureSettings(a: AndroidAccount) {
        val values = ContentValues().apply {
            put(Settings.ACCOUNT_NAME, a.name)
            put(Settings.ACCOUNT_TYPE, a.type)
            put(Settings.UNGROUPED_VISIBLE, 1)
            put(Settings.SHOULD_SYNC, 1)
        }
        val updated = cr.update(Settings.CONTENT_URI.asAdapter(a), values, "${Settings.ACCOUNT_NAME} = ? AND ${Settings.ACCOUNT_TYPE} = ?", arrayOf(a.name, a.type))
        if (updated == 0) cr.insert(Settings.CONTENT_URI.asAdapter(a), values)
    }

    /**
     * Writes raw contacts in batches. Each contact's operations start with
     * a check that it is still clean (or still new), so a contact someone
     * edits at that moment is left for the next run; a batch that fails on
     * that check is applied again contact by contact.
     */
    private inner class Writer(
        private val a: AndroidAccount,
        private val known: MutableMap<Pair<String, String>, Raw>,
        private val books: Map<String, Collection>,
    ) {
        val groups = GroupIds(a)
        private val batch = mutableListOf<List<ContentProviderOperation>>()
        private var size = 0

        /** Written this run but not yet in `known` (inserted in a batch not applied yet). */
        private val inserted = mutableSetOf<Pair<String, String>>()

        /** Photos to write once the raw contacts exist: book and name to base64 (null removes it). */
        private val photoQueue = mutableMapOf<Pair<String, String>, String?>()

        fun write(book: String, name: String, force: Boolean = false) {
            val key = book to name
            if (key in inserted) {
                flush()
                lookup(book, name)?.let { known[key] = it }
                inserted.remove(key)
            }
            val existing = known[key]
            val row = try {
                core.contactRow(book, name)
            } catch (e: CoreError) {
                Log.w(TAG, "a contact was not written to Android: ${e.javaClass.simpleName}")
                return
            }
            if (row == null) {
                existing?.takeIf { !it.dirty }?.let { remove(it.id) }
                known.remove(key)
                return
            }
            if (existing?.dirty == true && !force) return
            val readOnly = books[book]?.readOnly == true
            val rows = dataRows(row, groups::id)
            // A contact's operations stay in one batch: its back reference counts from the batch's start.
            if (size + rows.size + 3 > BATCH) flush()
            val ops = mutableListOf<ContentProviderOperation>()
            if (existing == null) {
                ops += ContentProviderOperation.newInsert(RawContacts.CONTENT_URI.asAdapter(a))
                    .withValue(RawContacts.ACCOUNT_NAME, a.name)
                    .withValue(RawContacts.ACCOUNT_TYPE, a.type)
                    .withValue(RawContacts.SOURCE_ID, name)
                    .withValue(RawContacts.SYNC1, book)
                    .withValue(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (readOnly) 1 else 0)
                    .build()
                for (d in rows) {
                    ops += ContentProviderOperation.newInsert(Data.CONTENT_URI.asAdapter(a))
                        .withValueBackReference(Data.RAW_CONTACT_ID, size)
                        .withValue(Data.MIMETYPE, d.mime)
                        .withValues(d.values.toValues())
                        .build()
                }
                inserted += key
            } else {
                val id = existing.id
                if (!force) {
                    ops += ContentProviderOperation.newAssertQuery(raw(id).asAdapter(a))
                        .withSelection("${RawContacts.DIRTY} = 0", null)
                        .withExpectedCount(1)
                        .build()
                }
                ops += ContentProviderOperation.newDelete(Data.CONTENT_URI.asAdapter(a))
                    .withSelection(
                        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${OWNED_MIMES.joinToString(",") { "?" }})",
                        arrayOf(id.toString()) + OWNED_MIMES,
                    )
                    .build()
                ops += ContentProviderOperation.newUpdate(raw(id).asAdapter(a))
                    .withValue(RawContacts.SYNC1, book)
                    .withValue(RawContacts.RAW_CONTACT_IS_READ_ONLY, if (readOnly) 1 else 0)
                    .withValue(RawContacts.DIRTY, 0)
                    .build()
                for (d in rows) {
                    ops += ContentProviderOperation.newInsert(Data.CONTENT_URI.asAdapter(a))
                        .withValue(Data.RAW_CONTACT_ID, id)
                        .withValue(Data.MIMETYPE, d.mime)
                        .withValues(d.values.toValues())
                        .build()
                }
            }
            add(ops)
            if (row.photo?.let(::hash) != existing?.photoHash || force) photoQueue[key] = row.photo
        }

        fun remove(id: Long) {
            add(listOf(ContentProviderOperation.newDelete(raw(id).asAdapter(a)).build()))
        }

        private fun add(ops: List<ContentProviderOperation>) {
            batch += ops
            size += ops.size
            if (size >= BATCH) flush()
        }

        fun flush() {
            if (batch.isEmpty()) return
            val all = ArrayList(batch.flatten())
            try {
                cr.applyBatch(ContactsContract.AUTHORITY, all)
            } catch (e: OperationApplicationException) {
                // Someone edited one of them meanwhile: apply the others one by one.
                for (ops in batch) {
                    runCatching { cr.applyBatch(ContactsContract.AUTHORITY, ArrayList(ops)) }
                }
            }
            batch.clear()
            size = 0
        }

        private fun lookup(book: String, name: String): Raw? =
            raws(a, "${RawContacts.SOURCE_ID} = ? AND ${RawContacts.SYNC1} = ?", arrayOf(name, book)).firstOrNull()

        /**
         * The queued photos, as display-photo files. Android processes each
         * one on its own and marks the contact dirty when done; the bridge
         * waits a little for that, then records the file id and clears the
         * mark. A photo still in progress stays `PENDING`, which the next
         * outbound takes as unchanged.
         */
        fun photos() {
            flush()
            val waiting = mutableMapOf<Long, String>()
            for ((key, photo) in photoQueue) {
                val r = lookup(key.first, key.second) ?: continue
                if (r.dirty) continue
                val uri = raw(r.id).asAdapter(a)
                if (photo == null) {
                    cr.delete(
                        Data.CONTENT_URI.asAdapter(a),
                        "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
                        arrayOf(r.id.toString(), Photo.CONTENT_ITEM_TYPE),
                    )
                    cr.update(uri, ContentValues().apply {
                        putNull(RawContacts.SYNC2)
                        putNull(RawContacts.SYNC3)
                        put(RawContacts.DIRTY, 0)
                    }, null, null)
                    continue
                }
                val bytes = runCatching { Base64.getMimeDecoder().decode(photo) }.getOrNull() ?: continue
                val written = runCatching {
                    cr.openAssetFileDescriptor(Uri.withAppendedPath(raw(r.id), RawContacts.DisplayPhoto.CONTENT_DIRECTORY).asAdapter(a), "rw")
                        ?.use { fd -> fd.createOutputStream().use { it.write(bytes) } } != null
                }.getOrDefault(false)
                cr.update(uri, ContentValues().apply {
                    put(RawContacts.SYNC2, hash(photo))
                    put(RawContacts.SYNC3, PENDING)
                }, null, null)
                if (written) waiting[r.id] = photo
            }
            photoQueue.clear()
            val until = System.currentTimeMillis() + PHOTO_WAIT_MS
            while (waiting.isNotEmpty() && System.currentTimeMillis() < until) {
                for (id in waiting.keys.toList()) {
                    val file = photoFile(id) ?: continue
                    cr.update(raw(id).asAdapter(a), ContentValues().apply {
                        put(RawContacts.SYNC3, file)
                        put(RawContacts.DIRTY, 0)
                    }, null, null)
                    waiting.remove(id)
                }
                if (waiting.isNotEmpty()) Thread.sleep(100)
            }
        }
    }

    companion object {
        private const val TAG = "CalZoneBridge"

        /** Bump when the mapping changes: every address book is written again. */
        const val VERSION = 1

        /** A photo written, its file not seen yet. */
        private const val PENDING = "pending"

        /** A photo row with a thumbnail only (no display-photo file). */
        private const val THUMBNAIL_ONLY = "thumbnail"

        /** `Groups.SYNC1` of the groups the bridge made for a vCard's CATEGORIES. */
        private const val MADE_BY_US = "calzone"

        /** Operations per batch: below the provider's limit, with room for one large contact. */
        private const val BATCH = 300

        private const val PHOTO_WAIT_MS = 5_000L

        val PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)

        /** A short fingerprint of a photo's base64 text (whitespace ignored). */
        fun hash(base64: String): String {
            val squashed = base64.filterNot { it.isWhitespace() }
            val d = MessageDigest.getInstance("SHA-256").digest(squashed.toByteArray())
            return d.take(12).joinToString("") { "%02x".format(it) }
        }
    }
}

private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

private fun Map<String, Any?>.toValues() = ContentValues().apply {
    for ((k, v) in this@toValues) {
        when (v) {
            null -> putNull(k)
            is String -> put(k, v)
            is Long -> put(k, v)
            is Int -> put(k, v)
            else -> error("unexpected column type for $k")
        }
    }
}
