package com.xblackbytesx.calzone.core

import com.xblackbytesx.calzone.model.Account
import com.xblackbytesx.calzone.model.Conflict
import com.xblackbytesx.calzone.model.ContactRow
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.EventRow
import com.xblackbytesx.calzone.model.EventRows
import com.xblackbytesx.calzone.model.LocalChange
import com.xblackbytesx.calzone.model.ObjectMeta
import com.xblackbytesx.calzone.model.Report
import com.xblackbytesx.calzone.model.SignedIn
import com.xblackbytesx.calzone.model.StoredCollection
import kotlinx.serialization.builtins.ListSerializer
import uniffi.calzone_ffi.CalZone
import uniffi.calzone_ffi.CzException
import uniffi.calzone_ffi.HttpTransport
import uniffi.calzone_ffi.parseLink

/** A sign-in address taken apart: the server's address and the one-time code. */
data class Link(val server: String, val code: String)

/** Why a call to the core failed, in the terms the app acts on. */
sealed class CoreError(message: String) : Exception(message) {
    /** No answer: try later, nothing was lost. */
    class Offline(message: String) : CoreError(message)

    /** The server no longer knows this phone: sign in again. */
    class SignedOut(message: String) : CoreError(message)

    /** An answer the app did not expect (an older server, a refused code). */
    class Server(message: String) : CoreError(message)

    /** A bug: something the app asked for cannot be done. */
    class Bug(message: String) : CoreError(message)
}

/**
 * The phone's store and sync engine (calzone-ffi, decision 48 in
 * calzone-server), typed. The ONLY user of `uniffi.calzone_ffi`; everything
 * else sees the app's models. Calls block: run them on [kotlinx.coroutines.Dispatchers.IO].
 */
class Core(path: String) {
    private val cz: CalZone = CalZone.open(path)

    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: CzException) {
        val m = e.message ?: ""
        throw when (e) {
            is CzException.Network -> CoreError.Offline(m)
            is CzException.SignedOut -> CoreError.SignedOut(m)
            is CzException.Server -> CoreError.Server(m)
            else -> CoreError.Bug(m)
        }
    }

    fun signIn(transport: HttpTransport, link: Link, device: String): SignedIn = call {
        CzJson.decodeFromString(SignedIn.serializer(), cz.signIn(transport, link.server, link.code, device))
    }

    fun account(): Account? = call { cz.account()?.let { CzJson.decodeFromString(Account.serializer(), it) } }

    fun server(): String? = call { cz.server() }

    fun refreshAccount(transport: HttpTransport): Account = call {
        CzJson.decodeFromString(Account.serializer(), cz.refreshAccount(transport))
    }

    /** Signs this phone out on the server when it can, and wipes the store. */
    fun signOut(transport: HttpTransport) = call { cz.signOut(transport) }

    fun registerPush(transport: HttpTransport, endpoint: String, key: String, auth: String) =
        call { cz.registerPush(transport, endpoint, key, auth) }

    fun unregisterPush(transport: HttpTransport) = call { cz.unregisterPush(transport) }

    fun sync(transport: HttpTransport): Report = call { CzJson.decodeFromString(Report.serializer(), cz.sync(transport)) }

    fun collections(): List<StoredCollection> =
        call { CzJson.decodeFromString(ListSerializer(StoredCollection.serializer()), cz.collections()) }

    fun objects(collection: String): List<ObjectMeta> =
        call { CzJson.decodeFromString(ListSerializer(ObjectMeta.serializer()), cz.objects(collection)) }

    fun conflicts(): List<Conflict> = call { CzJson.decodeFromString(ListSerializer(Conflict.serializer()), cz.conflicts()) }

    fun dismissConflict(id: Long) = call { cz.dismissConflict(id) }

    // --- the bridges (decisions 49 and 50 in calzone-server) ---

    /** Changes from the server after `after`, oldest first. */
    fun localChanges(after: Long, limit: Int): List<LocalChange> =
        call { CzJson.decodeFromString(ListSerializer(LocalChange.serializer()), cz.localChanges(after, limit.toUInt())) }

    fun forgetLocalChanges(upto: Long) = call { cz.forgetLocalChanges(upto) }

    /** The rows for a stored object in `zone`, or null (gone, or not an event). */
    fun eventRows(collection: String, name: String, zone: String): EventRows? =
        call { cz.eventRows(collection, name, zone)?.let { CzJson.decodeFromString(EventRows.serializer(), it) } }

    /** An edited row of a stored object; true when it changed something. */
    fun applyAndroidRow(collection: String, name: String, row: EventRow, zone: String): Boolean =
        call { cz.applyAndroidRow(collection, name, CzJson.encodeToString(EventRow.serializer(), row), zone) }

    /** A row another app made: a new object; its name. */
    fun createFromAndroidRow(collection: String, row: EventRow, zone: String, uid: String, fallbackTitle: String): String =
        call { cz.createFromAndroidRow(collection, CzJson.encodeToString(EventRow.serializer(), row), zone, uid, fallbackTitle) }

    fun deleteFromAndroid(collection: String, name: String, originalInstance: Long?, zone: String) =
        call { cz.deleteFromAndroid(collection, name, originalInstance, zone) }

    fun moveFromAndroid(from: String, to: String, name: String, row: EventRow, zone: String) =
        call { cz.moveFromAndroid(from, to, name, CzJson.encodeToString(EventRow.serializer(), row), zone) }

    /** The raw contact for a stored vCard, or null (gone, or a group card). */
    fun contactRow(collection: String, name: String): ContactRow? =
        call { cz.contactRow(collection, name)?.let { CzJson.decodeFromString(ContactRow.serializer(), it) } }

    /** An edited raw contact of a stored vCard; true when it changed something. */
    fun applyContactRow(collection: String, name: String, row: ContactRow): Boolean =
        call { cz.applyContactRow(collection, name, CzJson.encodeToString(ContactRow.serializer(), row)) }

    /** A raw contact another app made: a new vCard; its name. */
    fun createFromContactRow(collection: String, row: ContactRow, uid: String, fallbackName: String): String =
        call { cz.createFromContactRow(collection, CzJson.encodeToString(ContactRow.serializer(), row), uid, fallbackName) }

    /** Delete a stored object; it goes to the server with the next sync. */
    fun delete(collection: String, name: String) = call { cz.edit(collection, name, null) }

    companion object {
        /** The QR code's (or a pasted) address, or null when it is not a CalZone sign-in address. */
        fun link(url: String): Link? = parseLink(url)?.let { Link(it.server, it.code) }
    }
}
